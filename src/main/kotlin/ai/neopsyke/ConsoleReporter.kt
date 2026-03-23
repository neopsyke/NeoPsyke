package ai.neopsyke

interface ConsoleReporter {
    fun info(message: String)

    fun warn(message: String)

    fun error(message: String)
}

object StdConsoleReporter : ConsoleReporter {
    override fun info(message: String) {
        println(message)
    }

    override fun warn(message: String) {
        println(message)
    }

    override fun error(message: String) {
        System.err.println(message)
    }
}

