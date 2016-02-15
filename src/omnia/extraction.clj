(ns omnia.extraction)

(defn can-parse?
  "I think this really should be added to pantomime — what I really want to know is what mime types it’s capable
   of extracting text from. So that “knowledge” should be localized, contained within Pantomime alongside the
   functionality. But I don’t have time for that right now.

   I was just poking around in the Tika docs and actually it looks like there might be a way to
   extract this information from Tika programmatically:

   * The Tika interface [Parser](https://tika.apache.org/1.4/api/org/apache/tika/parser/Parser.html)
     has the method `getSupportedTypes` that returns `Set<MediaType>`.

   * The Tika class [AutoDetectParser](https://tika.apache.org/1.4/api/org/apache/tika/parser/AutoDetectParser.html)
     implements Parser

   * So instantiating AutoDetectParser and then calling `getSupportedTypes` might just be the ticket!"
  [mime-type]
  (true?
    (some #(.contains mime-type %)
          ["text"
           "pdf"
           "md"
           "msword"
           "wordprocessingml"
           "presentationml"
           "application/rtf"
           "application/epub+zip"
           "powerpoint"
           "opendocument.text"])))
