import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.FSDirectory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.nio.file.Path
import javax.xml.parsers.SAXParserFactory

fun deleteUnused(
    indexPathObj: Path?,
    list: Map<String, Set<String>>,
    isDry: Boolean
): Triple<Int, Int, Long> {
    var separateCount = 0
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
                        if (!isDry)
                            fileList.forEach {
                                it.deleteOnExit()
                            }
                        separateCount++
                        count += fileList.size
                    }
                }
            }
        }

    }
    return Triple(separateCount, count, space)
}



fun unusedDrawableFlow(reportRoot: File, reportXmlPath: String): MutableMap<String, MutableSet<String>> {
    val newInstance = SAXParserFactory.newInstance()
    val newSAXParser = newInstance.newSAXParser()
    val reportXmlFile = File(reportRoot, reportXmlPath)

    val handler = object : DefaultHandler() {
        val map = mutableMapOf<String, MutableSet<String>>()
        var printNextLocation = false
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
                        val key = File(message).drawableName()
                        map.getOrPut(key) { mutableSetOf() }.add(message)
                    }
                }

            }
        }
    }
    newSAXParser.parse(reportXmlFile, handler)
    return handler.map
}