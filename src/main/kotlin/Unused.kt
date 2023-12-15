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
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.Source
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

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
    tagName: String,
    visitor: (Attributes?, List<ResourceName>) -> Boolean,
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
                val unusedNavigation = list.filter { (name, group) ->
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
                count += unusedNavigation.size
                println(unusedNavigation)
                /**
                 * 转换key-value 的位置
                 */
                val pathKeyed = unusedNavigation.flatMap { entry ->
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
                separateCount += pathKeyed.size
                val saxParserFactory = SAXParserFactory.newInstance()
                space += pathKeyed.map { (path, u) ->
                    filterUnusedField(saxParserFactory, tagName, visitor, u, path, isDryRun)
                }.sum()
            }
        }

    }
    return Count(separateCount, count, space)
}

private fun filterUnusedField(
    saxParserFactory: SAXParserFactory,
    tagName: String,
    visitor: (Attributes?, List<ResourceName>) -> Boolean,
    u: List<ResourceName>,
    path: ResourcePath,
    isDryRun: Boolean
): Long {
    val xmlFilterImpl =
        object : XMLFilterImpl(saxParserFactory.newSAXParser().xmlReader) {
            private var skipDescendant = false

            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String,
                atts: Attributes
            ) {
                if (qName == tagName) {
                    val visitor1 = visitor(atts, u)
                    if (!visitor1) super.startElement(uri, localName, qName, atts)
                    skipDescendant = visitor1
                } else if (!skipDescendant) {
                    super.startElement(uri, localName, qName, atts)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (!skipDescendant) {
                    super.endElement(uri, localName, qName)
                }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (!skipDescendant) {
                    super.characters(ch, start, length)
                }
            }
        }
    val file = File(path)
    val transformer = TransformerFactory.newInstance().apply {
        setAttribute("indent-number", 4)
    }.newTransformer().apply {
        setOutputProperty(OutputKeys.INDENT, "yes")
    }


    val dest = File("$path.dest")
    file.inputStream().use { input ->
        dest.outputStream().use { output ->
            val saxSource = SAXSource(xmlFilterImpl, InputSource(input))
            val streamResult = StreamResult(output)
            transformer.transform(saxSource, streamResult)
        }
    }

    val space = file.length() - dest.length()
    if (!isDryRun) {
        dest.inputStream().use { input ->
            file.outputStream().use { output ->
                output.buffered().write(input.readBytes())
            }
        }
    }
    dest.delete()
    return space
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