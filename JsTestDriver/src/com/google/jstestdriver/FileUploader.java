// Copyright 2010 Google Inc. All Rights Reserved.

package com.google.jstestdriver;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.google.jstestdriver.JsonCommand.CommandType;
import com.google.jstestdriver.browser.BrowserFileSet;
import com.google.jstestdriver.servlet.fileset.BrowserFileCheck;
import com.google.jstestdriver.servlet.fileset.ServerFileCheck;
import com.google.jstestdriver.servlet.fileset.ServerFileUpload;
import com.google.jstestdriver.util.StopWatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles the uploading of files.
 *
 * @author corysmith@google.com (Cory Smith)
 */
public class FileUploader {
  public static final int CHUNK_SIZE = 50;

  private final StopWatch stopWatch;
  private final Gson gson = new Gson();
  private final Server server;
  private final String baseUrl;
  private final FileLoader fileLoader;
  private final JsTestDriverFileFilter filter;
  private static final Logger logger = LoggerFactory.getLogger(FileUploader.class);

  @Inject
  public FileUploader(StopWatch stopWatch, Server server, @Named("server") String baseUrl,
      FileLoader fileLoader, JsTestDriverFileFilter filter) {
    this.stopWatch = stopWatch;
    this.server = server;
    this.baseUrl = baseUrl;
    this.fileLoader = fileLoader;
    this.filter = filter;
  }

  /** Determines what files have been changed as compared to the server. */
  public List<FileInfo> determineBrowserFileSet(String browserId, Set<FileInfo> files,
      ResponseStream stream) {
    stopWatch.start("get upload set %s", browserId);
    Map<String, String> fileSetParams = new LinkedHashMap<String, String>();
    
    final List<FileInfo> serverable = Lists.newLinkedList();
    for (FileInfo fileInfo : files) {
      if (!fileInfo.isServeOnly()) {
        serverable.add(fileInfo);
      }
    }

    fileSetParams.put("id", browserId);
    fileSetParams.put("data", gson.toJson(serverable));
    fileSetParams.put("action", BrowserFileCheck.ACTION);
    String postResult = server.post(baseUrl + "/fileSet", fileSetParams);
    stopWatch.stop("get upload set %s", browserId);

    if (postResult.length() > 0) {
      stopWatch.start("resolving browser upload %s", browserId);
      BrowserFileSet browserFileSet = gson.fromJson(postResult, BrowserFileSet.class);
      logger.debug("Updating files {}", browserFileSet.getFilesToUpload());

      // need a linked hashset here to avoid adding a file more than once.
      final Set<FileInfo> finalFilesToUpload = new LinkedHashSet<FileInfo>();
      // reset if there are extra files in the browser
      if (!browserFileSet.getExtraFiles().isEmpty()) {
        reset(browserId, stream);
        // since the browser has been reset, reload all files.
        finalFilesToUpload.addAll(serverable);
      } else {
        for (FileInfo file : browserFileSet.getFilesToUpload()) {
          finalFilesToUpload.addAll(determineInBrowserDependencies(file));
        }
      }
      stopWatch.stop("resolving browser upload %s", browserId);
      return Lists.newArrayList(finalFilesToUpload);
    } else {
      logger.debug("No files to update on server.");
    }
    return Collections.<FileInfo>emptyList();
  }

  /** Uploads the changed files to the server and the browser. */
  public void uploadFileSet(String browserId, Set<FileInfo> files, ResponseStream stream) {

    stopWatch.start("determineServerFileSet(%s)", browserId);
    final List<FileInfo> serverFilesToUpdate = determineServerFileSet(files);
    stopWatch.stop("determineServerFileSet(%s)", browserId);
    
    stopWatch.start("upload to server %s", browserId);
    uploadToServer(serverFilesToUpdate);
    stopWatch.stop("upload to server %s", browserId);

    stopWatch.start("determineBrowserFileSet(%s)", browserId);
    final List<FileInfo> browserFilesToupdate = determineBrowserFileSet(browserId, files, stream);
    stopWatch.stop("determineBrowserFileSet(%s)", browserId);
    
    stopWatch.start("uploadToTheBrowser(%s)", browserId);
    uploadToTheBrowser(browserId, stream, browserFilesToupdate);
    stopWatch.stop("uploadToTheBrowser(%s)", browserId);
  }

  /**
   * Creates a loaded list of files that are out of date with the server cache.
   */
  private List<FileInfo> determineServerFileSet(Set<FileInfo> files) {
    Map<String, String> fileSetParams = new LinkedHashMap<String, String>();
    fileSetParams.put("data", gson.toJson(files));
    fileSetParams.put("action", ServerFileCheck.ACTION);
    String postResult = server.post(baseUrl + "/fileSet", fileSetParams);
    final Collection<FileInfo> filesToUpload =
        gson.fromJson(postResult, new TypeToken<Collection<FileInfo>>() {}.getType());
    if (logger.isDebugEnabled()) {
      logger.debug("Loading {} from disk",
          Lists.transform(Lists.newArrayList(files), new Function<FileInfo, String>() {
            public String apply(FileInfo in) {
              return "\n" + in.getFilePath();
            }
          }));
    }
    return fileLoader.loadFiles(filesToUpload, false);
  }

  /** Uploads files to the browser. */
  public void uploadToTheBrowser(String browserId, ResponseStream stream,
      List<FileInfo> loadedFiles) {
    List<FileSource> filesSrc = Lists.newLinkedList(filterFilesToLoad(loadedFiles));
    int numberOfFilesToLoad = filesSrc.size();
    logger.debug("Files toupload {}",
      Lists.transform(Lists.newArrayList(loadedFiles), new Function<FileInfo, String>() {
        public String apply(FileInfo in) {
          return "\n" + in.toString();
        }
      }));
    for (int i = 0; i < numberOfFilesToLoad; i += CHUNK_SIZE) {
      int chunkEndIndex = Math.min(i + CHUNK_SIZE, numberOfFilesToLoad);
      List<String> loadParameters = new LinkedList<String>();
      List<FileSource> filesToLoad = filesSrc.subList(i, chunkEndIndex);

      loadParameters.add(gson.toJson(filesToLoad));
      loadParameters.add("false");
      JsonCommand cmd = new JsonCommand(CommandType.LOADTEST, loadParameters);
      Map<String, String> loadFileParams = new LinkedHashMap<String, String>();

      loadFileParams.put("id", browserId);
      loadFileParams.put("data", gson.toJson(cmd));
      if (logger.isDebugEnabled()) {
        logger.debug("Sending LOADTEST to {} for {}", browserId,
            Lists.transform(filesToLoad, new Function<FileSource, String>() {
              public String apply(FileSource in) {
                return "\n" + in.getFileSrc();
              }
            }));
      }
      server.post(baseUrl + "/cmd", loadFileParams);
      String jsonResponse = server.fetch(baseUrl + "/cmd?id=" + browserId);
      StreamMessage message = gson.fromJson(jsonResponse, StreamMessage.class);
      Response response = message.getResponse();

      logger.debug("LOADTEST finished for ({}) {}", browserId, response.getBrowser());
      stream.stream(response);
    }
  }

  private void uploadToServer(final List<FileInfo> loadedFiles) {
    if (loadedFiles.isEmpty()) {
      return;
    }
    Map<String, String> uploadFileParams = new LinkedHashMap<String, String>();
    uploadFileParams.put("action", ServerFileUpload.ACTION);
    uploadFileParams.put("data", gson.toJson(loadedFiles));
    server.post(baseUrl + "/fileSet", uploadFileParams);
  }

  private void reset(String browserId, ResponseStream stream) {
    stopWatch.start("reset %s", browserId);
    JsonCommand cmd = new JsonCommand(CommandType.RESET, Collections.<String>emptyList());
    Map<String, String> resetParams = new LinkedHashMap<String, String>();

    resetParams.put("id", browserId);
    resetParams.put("data", gson.toJson(cmd));
    server.post(baseUrl + "/cmd", resetParams);

    logger.debug("Starting File Upload Refresh for {}", browserId);
    String jsonResponse = server.fetch(baseUrl + "/cmd?id=" + browserId);
    StreamMessage message = gson.fromJson(jsonResponse, StreamMessage.class);
    Response response = message.getResponse();
    stream.stream(response);
    logger.debug("Finished File Upload Refresh for {}", browserId);
    stopWatch.stop("reset %s", browserId);
  }

  /**
   * Determines what files must be reloaded in the browser, based on this file being updated.
   */
  private Collection<FileInfo> determineInBrowserDependencies(FileInfo file) {
    List<FileInfo> deps = new LinkedList<FileInfo>();
    for (FileInfo dep : filter.resolveFilesDeps(file)) {
      deps.add(dep);
    }
    return deps;
  }

  // TODO(corysmith): remove this function once FileInfo is used exclusively.
  // Hate static crap.
  private FileSource fileInfoToFileSource(FileInfo info) {
    if (info.getFilePath().startsWith("http://")) {
      return new FileSource(info.getFilePath(), info.getTimestamp());
    }
    return new FileSource("/test/" + info.getFilePath(), info.getTimestamp());
  }

  private List<FileSource> filterFilesToLoad(Collection<FileInfo> fileInfos) {
    List<FileSource> filteredFileSources = new LinkedList<FileSource>();

    for (FileInfo fileInfo : fileInfos) {
      if (!fileInfo.isServeOnly()) {
        filteredFileSources.add(fileInfoToFileSource(fileInfo));
      }
    }
    return filteredFileSources;
  }
}
