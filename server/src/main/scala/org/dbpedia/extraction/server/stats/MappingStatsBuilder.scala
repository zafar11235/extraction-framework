package org.dbpedia.extraction.server.stats

import java.io.{File,FileOutputStream,OutputStreamWriter}
import java.util.logging.Logger
import scala.collection.mutable
import org.dbpedia.extraction.destinations.Quad
import org.dbpedia.extraction.util.StringUtils.prettyMillis
import org.dbpedia.extraction.util.{Language,WikiUtil,IOUtils}
import org.dbpedia.extraction.util.RichFile.wrapFile

class MappingStatsBuilder(statsDir : File, language: Language, pretty: Boolean)
extends MappingStatsConfig(statsDir, language)
{
    private val logger = Logger.getLogger(getClass.getName)

    private val resourceUriPrefix = language.resourceUri.append("")

    def buildStats(redirectsFile: File, articleTemplatesFile: File, templateParametersFile: File, infoboxTestFile: File): Unit =
    {
        var templatesMap = new mutable.HashMap[String, TemplateStatsBuilder]()
        
        println("Reading redirects from " + redirectsFile)
        val redirects = loadTemplateRedirects(redirectsFile)
        println("Found " + redirects.size + " redirects")
        
        println("Using Template namespace " + templateNamespace + " for language " + language.wikiCode)
        
        println("Counting templates in " + articleTemplatesFile)
        countTemplates(articleTemplatesFile, templatesMap, redirects)
        println("Found " + templatesMap.size + " different templates")


        println("Loading property definitions from " + templateParametersFile)
        propertyDefinitions(templateParametersFile, templatesMap, redirects)

        println("Counting properties in " + infoboxTestFile)
        countProperties(infoboxTestFile, templatesMap, redirects)
        
        val wikiStats = new WikipediaStats(language, redirects.toMap, templatesMap.map(e => (e._1, e._2.build)).toMap)
        
        logger.info("Serializing "+language.wikiCode+" wiki statistics to " + mappingStatsFile)
        val output = new OutputStreamWriter(new FileOutputStream(mappingStatsFile), "UTF-8")
        try wikiStats.write(output) finally output.close()
    }

    private def eachLine(file: File)(process: String => Unit) : Unit = {
      val millis = System.currentTimeMillis
      var count = 0
      IOUtils.readLines(file) {
        line =>
        process(line)
        count += 1
        if (count % 1000000 == 0) {
            if (pretty) print(count+" lines\r")
            else println(count+" lines")
        }
      }
      println(count+" lines - "+prettyMillis(System.currentTimeMillis - millis))
    }

    private def loadTemplateRedirects(file: File): mutable.Map[String, String] =
    {
      val redirects = new mutable.HashMap[String, String]()
      eachLine(file) {
        line => line.trim match {
          case Quad(quad) if (quad.datatype == null) => {
            val templateName = cleanUri(quad.subject)
            if (templateName.startsWith(templateNamespace)) {
              redirects(templateName) = cleanUri(quad.value)
            }
          }
          case str => if (str.nonEmpty && ! str.startsWith("#")) throw new IllegalArgumentException("line did not match object triple syntax: " + line)
        }
      }
      
      redirects
    }
    
    /**
     * @param fileName name of file generated by InfoboxExtractor, e.g. infobox_properties_en.nt
     */
    private def countTemplates(file: File, resultMap: mutable.Map[String, TemplateStatsBuilder], redirects: mutable.Map[String, String]): Unit =
    {
      // iterate through infobox properties
      eachLine(file) {
        line => line.trim match {
          // predicate must be wikiPageUsesTemplate
          case Quad(quad) if (quad.datatype == null && unescape(quad.predicate).contains("wikiPageUsesTemplate")) =>
          {
            var templateName = cleanUri(quad.value)
            
            // resolve redirect for *object*
            templateName = redirects.getOrElse(templateName, templateName)

            // lookup the *object* in the resultMap, create a new TemplateStats object if not found,
            // and increment templateCount
            resultMap.getOrElseUpdate(templateName, new TemplateStatsBuilder).templateCount += 1
          }
          case str => if (str.nonEmpty && ! str.startsWith("#")) throw new IllegalArgumentException("line did not match object or datatype triple syntax: " + line)
        }
      }
    }
    
    /**
     * Our wikitext parser is not very precise - some template parameter names are broken.
     * Only use names that contain none of the following chars: { | }
     */
    private def goodProperty(name: String): Boolean =
    {
      for (c <- name) if (c == '{' || c == '|' || c == '}') return false
      return true
    }

    private def propertyDefinitions(file: File, resultMap: mutable.Map[String, TemplateStatsBuilder], redirects: mutable.Map[String, String]): Unit =
    {
      // iterate through template parameters
      eachLine(file) {
        line => line.trim match {
          case Quad(quad) =>
          {
            var templateName = cleanUri(quad.subject)
            val propertyName = cleanValue(quad.value)
            
            if (goodProperty(propertyName)) {
              
              // resolve redirect for *subject*
              templateName = redirects.getOrElse(templateName, templateName)
                  
              // lookup the *subject* in the resultMap
              // skip the templates that are not used in any page
              for (stats <- resultMap.get(templateName))
              {
                // add object to properties map with count 0
                stats.properties.put(propertyName, 0)
              }
              
            }
            
          }
          case str => if (str.nonEmpty && ! str.startsWith("#")) throw new IllegalArgumentException("line did not match datatype triple syntax: " + line)
        }
      }
    }

    private def countProperties(file: File, resultMap: mutable.Map[String, TemplateStatsBuilder], redirects: mutable.Map[String, String]) : Unit =
    {
        // iterate through infobox test
        eachLine(file) {
            line => line.trim match {
                case Quad(quad) if (quad.datatype != null) => {
                    var templateName = cleanUri(quad.predicate)
                    val propertyName = cleanValue(quad.value)
                    
                    // resolve redirect for template
                    templateName = redirects.getOrElse(templateName, templateName)

                    // lookup the template in the resultMap
                    // skip the templates that are not found (they don't occur in Wikipedia)
                    for(stats <- resultMap.get(templateName)) {
                        // lookup property in the properties map
                        // skip the properties that are not found with any count (they don't occur in the template definition)
                        if (stats.properties.contains(propertyName)) {
                            // increment count in properties map
                            stats.properties.put(propertyName, stats.properties(propertyName) + 1)
                        }
                    }
                }
                case str => if (str.nonEmpty && ! str.startsWith("#")) throw new IllegalArgumentException("line did not match datatype triple syntax: " + line)
            }
        }
    }
    
    private def stripUri(uri: String): String = {
        if (! uri.startsWith(resourceUriPrefix)) throw new Exception(uri)
        WikiUtil.wikiDecode(uri.substring(resourceUriPrefix.length))
    }
    
    private def cleanUri(uri: String) : String = cleanName(stripUri(unescape(uri)))
    
    private def cleanValue(value: String) : String = cleanName(unescape(value))
    
    // values may contain line breaks, which mess up our file format, so let's remove them.
    private def cleanName(name: String) : String = name.replaceAll("\r|\n", "")

    private def unescape(value: String): String = {
        val sb = new java.lang.StringBuilder

        val inputLength = value.length
        var offset = 0

        while (offset < inputLength)
        {
            val c = value.charAt(offset)
            if (c != '\\') sb append c
            else
            {
                offset += 1
                // FIXME: check string length 
                val specialChar = value.charAt(offset)
                specialChar match
                {
                    case '"' => sb append '"'
                    case 't' => sb append '\t'
                    case 'r' => sb append '\r'
                    case '\\' => sb append '\\'
                    case 'n' => sb append '\n'
                    case 'u' =>
                    {
                        offset += 1
                        // FIXME: check string length 
                        val codepoint = value.substring(offset, offset + 4)
                        val character = Integer.parseInt(codepoint, 16).asInstanceOf[Char]
                        sb append character
                        offset += 3
                    }
                    case 'U' =>
                    {
                        offset += 1
                        // FIXME: check string length 
                        val codepoint = value.substring(offset, offset + 8)
                        val character = Integer.parseInt(codepoint, 16)
                        sb appendCodePoint character
                        offset += 7
                    }
                }
            }
            offset += 1
        }
        sb.toString
    }
}
