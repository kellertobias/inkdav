package de.tobisk.inkdav.files

internal fun isDotFileName(name: String): Boolean = name.startsWith('.')

internal fun isDotPath(path: String): Boolean = path.substringAfterLast('/').startsWith('.')
