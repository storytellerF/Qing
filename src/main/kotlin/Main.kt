import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.FSDirectory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.util.LinkedList
import javax.xml.parsers.SAXParserFactory

fun main(args: Array<String>) {
    println("Program arguments: ${args.joinToString()}")
    val userHome = System.getProperty("user.home")
    val indexPath = File(userHome, ".index/mercury")
    indexPath.deleteRecursively()
    val projectDir = if (args.size == 1) {
        args.first()
    } else throw Exception()
    val moduleName = "app"
    val module = File(projectDir, moduleName)
    val codeFolder = File(module, "src/main/java")
    val layoutFolder = File(module, "src/main/res/layout")

    val stack = LinkedList<String>()
    stack.add(codeFolder.absolutePath)
    stack.add(layoutFolder.absolutePath)
    val srcFolders = mutableListOf<String>()
    while (stack.isNotEmpty()) {
        val pathname = stack.pollFirst()
        File(pathname).list()?.forEach {
            if (it != ".DS_Store") {
                val file = File(pathname, it)
                if (file.isFile) {
                    srcFolders.add(file.absolutePath)
                } else {
                    stack.add(file.absolutePath)
                }
            }
        }

    }
    val indexPathObj = indexPath.toPath()
    FSDirectory.open(indexPathObj).use { directory ->
        StandardAnalyzer().use { analyzer ->
            IndexWriter(directory, IndexWriterConfig(analyzer)).use { writer ->
                srcFolders.forEach {
                    val document = Document().apply {
                        add(StringField("path", it, Field.Store.YES))
                        add(TextField("content", File(it).readText(), Field.Store.NO))
                    }
                    writer.addDocument(document)
                }
            }
        }
    }


    val reportRoot = File(module, "build/reports/")
    val reportXmlPath = reportRoot.list { _, name ->
        name.endsWith("xml")
    }?.firstOrNull() ?: return
    val list = unusedDrawableFlow(reportRoot, reportXmlPath).distinct()
    var count = 0
    var space = 0L
    FSDirectory.open(indexPathObj).use { fsDirectory ->
        StandardAnalyzer().use { analyzer ->
            val parser = QueryParser("content", analyzer)
            DirectoryReader.open(fsDirectory).use { reader ->
                val searcher = IndexSearcher(reader)
                list.forEach {
                    val file = File(it)
                    val fullName = file.name
                    val realName = fullName.split(".").first()
                    if (listOf(realName, "R\\.drawable\\.$realName").all {
                            val docs = searcher.search(parser.parse(it), 1)
                            docs.scoreDocs.isEmpty()
                        }) {
                        space += file.length()
                        file.deleteOnExit()
                        count ++
                    }
                }
            }
        }

    }
    println("total ${list.size} delete $count space $space bytes")

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