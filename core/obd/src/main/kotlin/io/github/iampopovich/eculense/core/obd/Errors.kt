package io.github.iampopovich.eculense.core.obd

open class ObdException(message: String) : Exception(message)

class ElmTimeoutException(command: String) : ObdException("Адаптер не ответил на '$command'")
