(ns omnia.extraction)

(defn can-parse? [mime-type]
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
