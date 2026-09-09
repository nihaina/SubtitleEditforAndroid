package com.subtitleedit.util

import java.io.IOException

internal class ModelDownloadHttpException(val statusCode: Int) :
    IOException("模型下载失败：HTTP $statusCode")
