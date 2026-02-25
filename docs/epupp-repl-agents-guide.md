# Epupp REPL for AI Agents

A guide for AI agents using the Epupp browser REPL to interact with web pages on behalf of users.

## How It Works

Epupp injects a Scittle (ClojureScript) REPL into browser pages. AI agents can evaluate code via nREPL to read DOM content, scrape data, fill forms, and navigate - turning the browser into a programmable tool.

## The Navigation Pitfall

On non-SPA sites, navigating (`set!` on `js/window.location`) tears down the page and its REPL. If the navigation is triggered by an eval, the response never returns - the REPL connection hangs until it reconnects on the new page.

**Fix: Defer navigation with `setTimeout`**

```clojure
;; BAD - eval never completes, connection hangs
(set! (.-location js/window) "https://example.com/search?q=test")

;; GOOD - returns immediately, navigates 100ms later
(js/setTimeout
  #(set! (.-location js/window) "https://example.com/search?q=test")
  50)
;; => 42 (timeout ID returned instantly)
```

After navigation, wait a moment for the new page to load and the REPL to reconnect, then evaluate normally on the new page. Note that all definitions made up till then will be gone, and you'll need to redefine utility functions and such. If you see opportunity, you can suggest baking some utilities into a userscript that auto-runs on the site.

## Quick Patterns

### Check current page

```clojure
(.-title js/document)
```

### Scrape structured data

```clojure
(let [items (array-seq (js/document.querySelectorAll ".result-card"))
      results (atom [])]
  (doseq [item items]
    (let [text (.-textContent item)
          title-el (.querySelector item "[role='heading']")
          title (when title-el (.-textContent title-el))]
      (when title
        (swap! results conj {:title title}))))
  @results)
```

### Return data, don't print it

`prn` output may not be captured by the agent's tooling. Return values directly from your eval form instead.

```clojure
;; BAD - output may be lost
(prn "hello")

;; GOOD - returned as eval result
"hello"
```

## Tips

- **Scittle property access**: Use `(.-title js/document)`, not `(js/document.title)` for property access. Method calls like `(.querySelector el "css")` work fine.
- **eBay-style sites**: Modern sites change DOM class names. Inspect structure with broad selectors first, then narrow down.
- **Large results**: Return only the data you need. Truncate titles, limit result counts with `take`.
- **Regex on text content**: When structured selectors fail, fall back to regex on `(.-textContent el)` to extract prices, dates, etc.
