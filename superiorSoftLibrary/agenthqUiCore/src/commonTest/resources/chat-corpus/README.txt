Chat parsing corpus. Every *.txt file here is one real (scrubbed) message body. ChatCorpusTest runs each one through the
full chat path (chatLineBlocks) for the built-in engine AND the example plugin engine and checks, with no per-file setup:
  - no engine throws, for the whole text and for every prefix of it (a reply streaming in)
  - the Comms header ('[Infantry AI COMMS] message ...') and footer ('-- answer this message --') stay VISIBLE as plain text
  - a fenced region stays literal inside a Code block (closed or not)
  - a pipe table (header row + separator row) becomes a Table block, and its separator row is not shown as text
Found a message that rendered wrong? Save its text as a new .txt file here (name it for the problem). That is the whole change.
