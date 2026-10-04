package com.example.aicodebackend.service;

import jakarta.servlet.http.HttpServletResponse;

public interface ProjectDownloadService {

    void downloadProjectAsZip(String projectPath, String downloadFilename, HttpServletResponse response);
}
