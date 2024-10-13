package info.meuse24.smsforwarderneo

import android.content.Context
import android.util.Log
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlinx.coroutines.*

class Logger(private val context: Context, private val maxEntries: Int = 1000) {
    private val logFile: File = File(context.getExternalFilesDir(null), "app_log.xml")
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    private val transformer = TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.INDENT, "yes")
    }

    private val logBuffer = mutableListOf<LogEntry>()
    private var lastSaveTime = System.currentTimeMillis()
    private val saveInterval = 60000 // 1 minute in milliseconds

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastError: LoggerException? = null

    init {
        try {
            if (!logFile.exists() || !loadExistingLogs()) {
                createNewLogFile()
            }
        } catch (e: Exception) {
            handleException(e, "Initialization failed")
            createNewLogFile()
        }
    }

    private fun loadExistingLogs(): Boolean {
        return try {
            val doc = documentBuilder.parse(logFile)
            val entries = doc.documentElement.getElementsByTagName("logEntry")
            logBuffer.clear() // Clear existing buffer before loading
            for (i in 0 until entries.length) {
                val entry = entries.item(i) as Element
                val time = entry.getElementsByTagName("time").item(0).textContent
                val text = entry.getElementsByTagName("text").item(0).textContent
                logBuffer.add(LogEntry(time, text))
            }
            true // Successfully loaded
        } catch (e: Exception) {
            handleException(e, "Failed to load existing logs, creating new file")
            false // Failed to load
        }
    }

    private fun createNewLogFile() {
        try {
            logBuffer.clear() // Ensure buffer is clear when creating new file
            val doc = documentBuilder.newDocument()
            doc.appendChild(doc.createElement("logEntries"))
            saveDocumentToFile(doc)
            addLogEntry("New log file created due to loading failure or non-existence")
        } catch (e: Exception) {
            handleException(e, "Failed to create new log file")
        }
    }

    fun addLogEntry(entry: String) {
        try {
            val timestamp = getCurrentTimestamp()
            logBuffer.add(LogEntry(timestamp, entry))
            if (logBuffer.size > maxEntries) {
                logBuffer.removeAt(0)
            }

            if (System.currentTimeMillis() - lastSaveTime > saveInterval) {
                coroutineScope.launch { saveLogsToFile() }
            }
        } catch (e: Exception) {
            handleException(e, "Failed to add log entry")
        }
    }

    fun clearLog() {
        try {
            logBuffer.clear()
            createNewLogFile()
        } catch (e: Exception) {
            handleException(e, "Failed to clear log")
        }
    }

    fun getLogEntries(): String = try {
        buildString {
            logBuffer.forEach { (time, text) ->
                append("$time - $text\n")
            }
        }
    } catch (e: Exception) {
        handleException(e, "Failed to get log entries")
        "Error: Unable to retrieve log entries"
    }

    fun getLogEntriesHtml(): String = buildString {
        append("""
    <!DOCTYPE html>
    <html lang="de">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
        <title>Log-Einträge</title>
        <style>
            body {
                font-family: Arial, sans-serif;
                font-size: 16px;
                line-height: 1.6;
                margin: 0;
                padding: 0;
                background-color: #f0f0f0;
            }
            .container {
                max-width: 100%;
                overflow-x: auto;
                background-color: white;
                box-shadow: 0 0 10px rgba(0,0,0,0.1);
            }
            table {
                width: 100%;
                border-collapse: collapse;
                margin-bottom: 20px;
            }
            th, td {
                padding: 12px;
                text-align: left;
                border-bottom: 1px solid #ddd;
            }
            th {
                background-color: #4CAF50;
                color: white;
                font-weight: bold;
                position: sticky;
                top: 0;
            }
            tr:nth-child(even) {
                background-color: #f2f2f2;
            }
            tr:hover {
                background-color: #ddd;
            }
            .time-column {
                font-size: 12px; /* Kleinere Schriftgröße für die Zeitspalte */
                white-space: nowrap; /* Verhindert Umbruch des Zeitstempels */
            }
            @media screen and (max-width: 600px) {
                body {
                    font-size: 14px;
                }
                th, td {
                    padding: 8px;
                }
                .time-column {
                    font-size: 10px; /* Noch kleinere Schrift auf kleinen Bildschirmen */
                }
            }
        </style>
    </head>
    <body>
        <div class="container">
            <table>
                <thead>
                    <tr>
                        <th class="time-column">Zeit</th>
                        <th>Eintrag</th>
                    </tr>
                </thead>
                <tbody>
    """.trimIndent())

        logBuffer.asReversed().forEach { (time, text) ->
            append("<tr><td class=\"time-column\">$time</td><td>$text</td></tr>")
        }

        append("""
                </tbody>
            </table>
        </div>
    </body>
    </html>
    """.trimIndent())
    }

    fun saveLogsToFile() {
        try {
            val doc = documentBuilder.newDocument()
            val rootElement = doc.createElement("logEntries")
            doc.appendChild(rootElement)

            logBuffer.forEach { (time, text) ->
                val logEntryElement = doc.createElement("logEntry")
                logEntryElement.appendChild(createElementWithText(doc, "time", time))
                logEntryElement.appendChild(createElementWithText(doc, "text", text))
                rootElement.appendChild(logEntryElement)
            }

            saveDocumentToFile(doc)
            lastSaveTime = System.currentTimeMillis()
        } catch (e: Exception) {
            handleException(e, "Failed to save logs to file")
        }
    }

    private fun saveDocumentToFile(doc: Document) {
        try {
            FileOutputStream(logFile).use { fos ->
                transformer.transform(DOMSource(doc), StreamResult(fos))
            }
        } catch (e: Exception) {
            handleException(e, "Failed to save document to file")
        }
    }

    private fun createElementWithText(doc: Document, tagName: String, textContent: String): Element {
        return doc.createElement(tagName).apply {
            appendChild(doc.createTextNode(textContent))
        }
    }

    private fun getCurrentTimestamp(): String = dateFormat.format(Date())

    private data class LogEntry(val time: String, val text: String)

    fun onDestroy() {
        coroutineScope.launch {
            try {
                saveLogsToFile()
            } catch (e: Exception) {
                handleException(e, "Failed to save logs during onDestroy")
            } finally {
                coroutineScope.cancel()
            }
        }
    }

    private fun handleException(e: Exception, message: String) {
        val loggerException = LoggerException(message, e)
        lastError = loggerException
        Log.e("Logger", message, e)
        Log.w("Logger", "Logger Error: $message - ${e.message}")
    }

    fun getLastError(): LoggerException? = lastError

    class LoggerException(message: String, cause: Throwable?) : Exception(message, cause)
}