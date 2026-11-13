Hello card (Acme_HelloCard): a bordered greeting box with a bold title and one line of text.

Write it as its own fenced block in your reply, info string `card:hello`, body ONE JSON object:

```card:hello
{"title": "Hello from Infantry", "text": "A short line under the title.", "image": "https://upload.wikimedia.org/wikipedia/commons/thumb/a/a7/Camponotus_flavomarginatus_ant.jpg/120px-Camponotus_flavomarginatus_ant.jpg"}
```

Fields: `title` (string, shown bold with a wave; default "Hello"), `text` (string, optional), `image` (an https URL on
upload.wikimedia.org or *.gstatic.com, optional: a thumbnail on the left; the daemon fetches it, any other host shows a letter badge).
Keep plain sentences outside the block. Without the plugin the block shows as code, so the reply still reads.
