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
    indexPath: Path?,
    list: Resources,
    isDryRun: Boolean,
    buildTerm: (String) -> List<String>
): Triple<Int, Int, Long> {
    var separateCount = 0
    var count = 0
    var space = 0L
    FSDirectory.open(indexPath).use { fsDirectory ->
        StandardAnalyzer().use { analyzer ->
            val parser = QueryParser("content", analyzer)
            DirectoryReader.open(fsDirectory).use { reader ->
                val searcher = IndexSearcher(reader)
                list.forEach { (name, group) ->
                    if (buildTerm(name).all {
                            try {
                                val docs = searcher.search(parser.parse(it), 1)
                                docs.scoreDocs.isEmpty()
                            } catch (e: Exception) {
                                println("when $it")
                                System.err.println(e)
                                false
                            }

                        }) {
                        val fileList = group.map {
                            File(it)
                        }
                        space += fileList.map {
                            it.length()
                        }.reduce { acc, file ->
                            acc + file
                        }
                        if (isDryRun)
                            fileList.forEach {
                                println(it.absolutePath)
                            }
                        else
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


fun unusedResourcesFlowFromLint(reportRoot: File, reportXmlPath: String): Resources {
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
                    if (message != null) {
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