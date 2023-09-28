import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.FSDirectory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.nio.file.Path
import java.util.LinkedList
import javax.xml.parsers.SAXParserFactory
import kotlin.system.exitProcess

enum class FileChangeMode {
    new, change, delete
}

fun main(args: Array<String>) {
    println("Program arguments: ${args.joinToString()}")
    val moduleName = "app"
    val projectDir = if (args.size == 1) {
        args.first()
    } else throw Exception()
    val module = File(projectDir, moduleName)
    val indexPath = File(System.getProperty("user.home"), ".index/mercury")
    val shaFlag = File(indexPath, "qing")
    if (!shaFlag.exists()) {
        val parentFile = shaFlag.parentFile
        if (!parentFile.exists()) {
            if (!parentFile.mkdirs()) {
                return
            }
        }
        if (!shaFlag.createNewFile()) {
            return
        }
    }
    val oldSha = shaFlag.readText()

    val project = File(projectDir)
    val start = ProcessBuilder("git", "rev-parse", "HEAD").directory(project).start()
    val waitFor = start.waitFor()
    if (waitFor != 0) exitProcess(waitFor)
    val currentSha = start.inputStream.bufferedReader().readText().trim()

    if (oldSha == currentSha) {
        println("not change")
        return
    }
    println("old $oldSha new $currentSha")

    val srcFolders = refreshFolders(oldSha, currentSha, project, module)

    println(srcFolders)
    val indexPathObj = indexPath.toPath()
    refreshIndex(indexPathObj, srcFolders)
    shaFlag.writeText(currentSha)


    val reportRoot = File(module, "build/reports/")
    val reportXmlPath = reportRoot.list { _, name ->
        name.endsWith("xml")
    }?.firstOrNull() ?: return
    val list = unusedDrawableFlow(reportRoot, reportXmlPath).groupBy {
        File(it).name.split(".").first()
    }
    var count = 0
    var space = 0L
    FSDirectory.open(indexPathObj).use { fsDirectory ->
        StandardAnalyzer().use { analyzer ->
            val parser = QueryParser("content", analyzer)
            DirectoryReader.open(fsDirectory).use { reader ->
                val searcher = IndexSearcher(reader)
                list.forEach { (name, group) ->
                    if (listOf(name, "R.drawable.$name").all {
                            val docs = searcher.search(parser.parse(it), 1)
                            docs.scoreDocs.isEmpty()
                        }) {
                        val fileList = group.map {
                            File(it)
                        }
                        space += fileList.map {
                            it.length()
                        }.reduce { acc, file ->
                            acc + file
                        }
                        fileList.forEach {
                            it.deleteOnExit()
                        }
                        count++
                    }
                }
            }
        }

    }
    println("total ${list.size} delete $count space $space bytes")

}

private fun refreshFolders(
    oldSha: String,
    currentSha: String,
    project: File,
    module: File
): List<Pair<String, FileChangeMode>> {
    val srcFolders = if (oldSha.isNotEmpty()) {
        val predicate: (String) -> Boolean = {
            it.endsWith(".kt") || it.endsWith(".xml")
        }
        strings(oldSha, currentSha, project, "M").filter(predicate).map {
            File(project, it).absolutePath to FileChangeMode.change
        } + strings(oldSha, currentSha, project, "A").filter(predicate).map {
            File(project, it).absolutePath to FileChangeMode.new
        } + strings(oldSha, currentSha, project, "D").filter(predicate).map {
            File(project, it).absolutePath to FileChangeMode.delete
        }
    } else {
        val codeFolder = File(module, "src/main/java")
        val layoutFolder = File(module, "src/main/res/layout")

        val stack = LinkedList<String>()
        stack.add(codeFolder.absolutePath)
        stack.add(layoutFolder.absolutePath)
        val srcFolders = mutableListOf<Pair<String, FileChangeMode>>()
        while (stack.isNotEmpty()) {
            val pathname = stack.pollFirst()
            File(pathname).list()?.forEach {
                val file = File(pathname, it)
                if (file.isFile) {
                    srcFolders.add(file.absolutePath to FileChangeMode.new)
                } else {
                    stack.add(file.absolutePath)
                }
            }

        }
        srcFolders
    }
    return srcFolders
}

private fun strings(
    oldSha: String,
    currentSha: String,
    project: File,
    mode: String
): List<String> {
    val process =
        ProcessBuilder("git", "diff", oldSha, currentSha, "--name-only", "--diff-filter=$mode").directory(project).start()
    val processResult = process.waitFor()
    if (processResult != 0) {
        exitProcess(processResult)
    }
    val text = process.inputStream.bufferedReader().readText().trim()
    return text.split("\n").filter {
        it.isNotEmpty()
    }
}

private fun refreshIndex(indexPathObj: Path?, srcFolders: List<Pair<String, FileChangeMode>>) {
    FSDirectory.open(indexPathObj).use { directory ->
        StandardAnalyzer().use { analyzer ->
            IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
                srcFolders.forEach { (it, mode) ->
                    val file = File(it)
                    when (mode) {
                        FileChangeMode.delete -> writer.deleteDocuments(Term("path", it))
                        FileChangeMode.new -> writer.addDocument(Document().apply {
                            add(StringField("path", it, Field.Store.YES))
                            add(TextField("content", file.readText(), Field.Store.NO))
                        })
                        else -> writer.updateDocument(Term("path", it), listOf(TextField("content", file.readText(), Field.Store.NO)))
                    }
                }
            }
        }
    }
}

private fun unusedDrawableFlow(reportRoot: File, reportXmlPath: String): MutableList<String> {
    val newInstance = SAXParserFactory.newInstance()
    val newSAXParser = newInstance.newSAXParser()
    val reportXmlFile = File(reportRoot, reportXmlPath)


    val list = mutableListOf<String>()
    var printNextLocation = false

    val handler = object : DefaultHandler() {
        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            super.startElement(uri, localName, qName, attributes)
            if (qName == "issue") {
                if (attributes?.getValue("id") == "UnusedResources") {
                    printNextLocation = true
                } else {
                    skippedEntity(qName)
                }
            } else if (qName == "location") {
                if (printNextLocation) {
                    val message = attributes?.getValue("file")
                    if (message?.endsWith("png") == true) {
                        list.add(message)
                    }
                }

            }
        }
    }
    newSAXParser.parse(reportXmlFile, handler)
    return list
}