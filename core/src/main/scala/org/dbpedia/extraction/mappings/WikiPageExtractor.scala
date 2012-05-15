package org.dbpedia.extraction.mappings

import org.dbpedia.extraction.destinations.{DBpediaDatasets, Quad}
import org.dbpedia.extraction.wikiparser._
import org.dbpedia.extraction.ontology.Ontology
import org.dbpedia.extraction.util.Language
import scala.collection.mutable.ArrayBuffer

/**
 * Extracts links to corresponding Articles in Wikipedia.
 */
class WikiPageExtractor( context : {
                            def ontology : Ontology
                            def language : Language } ) extends Extractor
{
    // FIXME: the combination of foaf:page and foaf:primaryTopic is probably wrong
    // http://xmlns.com/foaf/spec/#term_page says: foaf:page is inverse of foaf:topic
    // http://xmlns.com/foaf/spec/#term_primaryTopic says: foaf:primaryTopic is inverse of foaf:isPrimaryTopicOf
    // foaf:primaryTopic and foaf:isPrimaryTopicOf would probably be correct.
    private val foafPageProperty = context.ontology.properties("foaf:page")
    private val foafPrimaryTopicProperty = context.ontology.properties("foaf:primaryTopic")
    private val dcLanguageProperty = context.ontology.properties("dc:language")

    override def extract(node : PageNode, subjectUri : String, pageContext : PageContext): Seq[Quad] =
    {
        if(node.title.namespace != Namespace.Main) return Seq.empty
        
        val quads = new ArrayBuffer[Quad]()

        val objectLink = "http://" + context.language.wikiCode + ".wikipedia.org/wiki/" + node.root.title.encoded

        quads += new Quad(context.language, DBpediaDatasets.LinksToWikipediaArticle, subjectUri, foafPageProperty,  objectLink, node.sourceUri)
        quads += new Quad(context.language, DBpediaDatasets.LinksToWikipediaArticle, objectLink, foafPrimaryTopicProperty, subjectUri, node.sourceUri)
        quads += new Quad(context.language, DBpediaDatasets.LinksToWikipediaArticle, objectLink, dcLanguageProperty,  context.language.wikiCode, node.sourceUri)

        quads
    }
}
