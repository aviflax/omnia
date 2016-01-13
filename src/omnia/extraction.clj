(ns omnia.extraction)

(defn can-parse?
  "I think this really should be added to pantomime — what I really want to know is what mime types it’s capable
   of extracting text from. So that “knowledge” should be localized, contained within Pantomime alongside the
   functionality. But I don’t have time for that right now."
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
           "powerpoint"
           "opendocument.text"])))
