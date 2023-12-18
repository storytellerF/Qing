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

/**
 * @param visitor 返回是否需要排除
 */
fun deleteUnusedXmlField(
    indexPath: Path?,
    list: Resources,
    isDryRun: Boolean,
    tagName: String,
    visitor: (Attributes?, List<ResourceName>, IndexSearcher, QueryParser) -> Boolean,
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
                            val scoreDocs = searcher.search(parser.parse(it), 100).scoreDocs
                            if (it == "navigation_discover" || it == "DiscoverFragment") {
                                scoreDocs.forEach {
                                    println(searcher.doc(it.doc))
                                }
                            }
                            scoreDocs.isEmpty()
                        } catch (e: Exception) {
                            System.err.println(Exception("exception in search $it", e))
                            false
                        }
                    }
                }
                count += unusedNavigation.size
                if (isDryRun && unusedNavigation.isNotEmpty()) {
                    unusedNavigation.forEach { t, u ->
                        println(t)
                        println(u)
                    }
                }
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
                    filterUnusedField(saxParserFactory, tagName, visitor, u, path, isDryRun, searcher, parser)
                }.sum()
            }
        }

    }
    return Count(separateCount, count, space)
}

private fun filterUnusedField(
    saxParserFactory: SAXParserFactory,
    tagName: String,
    visitor: (Attributes?, List<ResourceName>, IndexSearcher, QueryParser) -> Boolean,
    u: List<ResourceName>,
    path: ResourcePath,
    isDryRun: Boolean,
    indexSearcher: IndexSearcher,
    queryParser: QueryParser,
): Long {
    val xmlFilterImpl =
        object : XMLFilterImpl(saxParserFactory.newSAXParser().xmlReader) {
            private var skipDescendant: String? = null

            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String,
                atts: Attributes
            ) {
                if (skipDescendant == null) {
                    if (qName == tagName) {
                        val exclude = visitor(atts, u, indexSearcher, queryParser)
                        if (!exclude) super.startElement(uri, localName, qName, atts)
                        skipDescendant = if (exclude) qName else null
                    } else {
                        super.startElement(uri, localName, qName, atts)
                    }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (skipDescendant == null) {
                    super.endElement(uri, localName, qName)
                } else if (qName == skipDescendant) {
                    skipDescendant = null
                }
            }

            override fun characters(ch: CharArray?, start: Int, length: Int) {
                if (skipDescendant == null) {
                    super.characters(ch, start, length)
                }
            }
        }
    val stylesheetFile = File("src/main/resources/stylesheet")
    val transformer = TransformerFactory.newInstance().newTransformer(StreamSource(stylesheetFile))

    val file = File(path)
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
                output.buffered().let {
                    it.write(input.readBytes())
                    it.flush()
                }
            }
        }
        dest.delete()
    }
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