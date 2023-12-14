import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.FSDirectory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.helpers.XMLFilterImpl
import java.io.File
import java.nio.file.Path
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stream.StreamResult

data class Count(val separate: Int, val total: Int, val space: Long)

fun deleteUnused(
    indexPath: Path?,
    list: Resources,
    isDryRun: Boolean,
    buildTerm: (String) -> List<String>
): Count {
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
                        val longs = if (isDryRun)
                            fileList.map {
                                val length = it.length()
                                println("delete ${it.absolutePath}")
                                length
                            }
                        else
                            fileList.map {
                                val length = it.length()
                                it.deleteOnExit()
                                length
                            }
                        space += longs.reduce { acc, file ->
                            acc + file
                        }
                        separateCount++
                        count += fileList.size
                    }
                }
            }
        }

    }
    return Count(separateCount, count, space)
}

fun deleteUnusedXmlField(
    indexPath: Path?,
    list: Resources,
    isDryRun: Boolean,
    module: File,
    buildTerm: (String) -> List<String>
): Count {
    var separateCount = 0
    var count = 0
    var space = 0L
    FSDirectory.open(indexPath).use { fsDirectory ->
        StandardAnalyzer().use { analyzer ->
            val parser = QueryParser("content", analyzer)
            DirectoryReader.open(fsDirectory).use { reader ->
                val searcher = IndexSearcher(reader)
                val neverUsedNavigation = list.filter { (name, group) ->
                    buildTerm(name).all {
                        try {
                            val docs = searcher.search(parser.parse(it), 1)
                            docs.scoreDocs.isEmpty()
                        } catch (e: Exception) {
                            println("when $it")
                            System.err.println(e)
                            false
                        }
                    }
                }
                count += neverUsedNavigation.size
                println(neverUsedNavigation)
                /**
                 * 转换key-value 的位置
                 */
                val flatMap = neverUsedNavigation.flatMap { entry ->
                    entry.value.map {
                        it to entry.key
                    }
                }.groupBy { (path) ->
                    path
                }.mapValues {
                    it.value.map { (_, name) ->
                        name
                    }
                }
                separateCount += flatMap.size
                flatMap.forEach { (t, u) ->
                    val ids = u.map {
                        it.split("-").first()
                    }
                    val xmlFilterImpl =
                        object : XMLFilterImpl(SAXParserFactory.newInstance().newSAXParser().xmlReader) {
                            private var skip = false

                            override fun startElement(
                                uri: String?,
                                localName: String?,
                                qName: String,
                                atts: Attributes
                            ) {
                                if (qName == "fragment") {
                                    val value = atts.getValue("android:id")?.let {
                                        it.substring(it.lastIndexOf("/") + 1)
                                    }
                                    if (ids.contains(value)) {
                                        val name = atts.getValue("android:name").replace(".", "/")
                                        val path = File(module, "src/main/java/${name}.kt")
                                        space += path.length()
                                        if (isDryRun)
                                            println("delete $path")
                                        else {
                                            println(path)
                                            path.delete()
                                        }
                                        skip = true
                                    } else {
                                        super.startElement(uri, localName, qName, atts)
                                        skip = false
                                    }
                                } else {
                                    if (!skip) {
                                        super.startElement(uri, localName, qName, atts)
                                    }
                                }
                            }

                            override fun endElement(uri: String?, localName: String?, qName: String?) {
                                if (!skip) {
                                    super.endElement(uri, localName, qName)
                                }
                            }

                            override fun characters(ch: CharArray?, start: Int, length: Int) {
                                if (!skip) {
                                    super.characters(ch, start, length)
                                }
                            }
                        }
                    val file = File(t)
                    val dest = File("$t.dest")
                    file.inputStream().use { input ->
                        dest.outputStream().use { output ->
                            val saxSource = SAXSource(xmlFilterImpl, InputSource(input))
                            val streamResult = if (isDryRun) StreamResult(System.out) else StreamResult(output)
                            TransformerFactory.newInstance().newTransformer().transform(saxSource, streamResult)
                        }

                    }
                    dest.inputStream().use { input ->
                        file.outputStream().use { output ->
                            output.buffered().write(input.readBytes())
                        }
                    }
                    dest.delete()
                }
            }
        }

    }
    return Count(separateCount, count, space)
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