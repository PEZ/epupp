/**
 * REPL Integration Tests for Playwright UI
 *
 * These tests mirror the Babashka tests in repl_test.clj but run through
 * Playwright's test framework, enabling the --ui interactive runner.
 *
 * Run with: bb test:repl-e2e:ui
 * (This task starts browser-nrepl and HTTP server automatically)
 */

import { test, expect, chromium, BrowserContext } from "@playwright/test";
import * as net from "net";
import * as path from "path";

const NREPL_PORT = 12345;
const WS_PORT = 12346;
const HTTP_PORT = 8765;

let context: BrowserContext | null = null;

// Helper to evaluate code via nREPL
function evalInBrowser(code: string): Promise<{
  success: boolean;
  values?: string[];
  error?: string;
}> {
  return new Promise((resolve) => {
    const client = net.createConnection({ port: NREPL_PORT, host: "localhost" });
    let response = "";

    client.on("data", (data) => {
      response += data.toString();

      // Check if response contains "done" status (bencode: 6:status...4:done)
      // This indicates the nREPL response is complete
      if (response.includes("4:done")) {
        client.destroy(); // Force close the connection

        const values: string[] = [];
        let success = true;
        let error: string | undefined;

        // Parse bencode values properly using length prefix
        // Format: 5:value<len>:<content> where <len> is the string length
        const valueRegex = /5:value(\d+):/g;
        let match;
        while ((match = valueRegex.exec(response)) !== null) {
          const len = parseInt(match[1]);
          const startIdx = match.index + match[0].length;
          const value = response.substring(startIdx, startIdx + len);
          values.push(value);
        }

        if (response.includes("2:ex") || response.includes("3:err")) {
          success = false;
          const errMatch = response.match(/3:err(\d+):/);
          if (errMatch) {
            const errLen = parseInt(errMatch[1]);
            const errStart = errMatch.index! + errMatch[0].length;
            error = response.substring(errStart, errStart + errLen);
          } else {
            error = "Unknown error";
          }
        }

        resolve({ success, values, error });
      }
    });

    client.on("error", (err) => {
      resolve({ success: false, error: err.message });
    });

    const msg = `d2:op4:eval4:code${code.length}:${code}e`;
    client.write(msg);
  });
}

test.describe("REPL Integration", () => {
  test.beforeAll(async () => {
    // Launch browser with extension (servers started by bb task)
    const extensionPath = path.resolve("dist/chrome");
    context = await chromium.launchPersistentContext("", {
      headless: false,
      args: [
        "--no-sandbox",
        "--allow-file-access-from-files",
        "--enable-features=ExtensionsManifestV3Only",
        `--disable-extensions-except=${extensionPath}`,
        `--load-extension=${extensionPath}`,
      ],
    });

    // Get extension ID
    let extId: string;
    const workers = context.serviceWorkers();
    if (workers.length > 0) {
      extId = workers[0].url().split("/")[2];
    } else {
      const sw = await context.waitForEvent("serviceworker");
      extId = sw.url().split("/")[2];
    }

    // Open test page
    const testPage = await context.newPage();
    await testPage.goto(`http://localhost:${HTTP_PORT}/`);

    // Connect via background APIs
    const bgPage = await context.newPage();
    await bgPage.goto(`chrome-extension://${extId}/popup.html`, { waitUntil: "networkidle" });
    await new Promise((r) => setTimeout(r, 2000));

    const findResult = await bgPage.evaluate(async (urlPattern: string) => {
      return new Promise((resolve) => {
        chrome.runtime.sendMessage({ type: "e2e/find-tab-id", urlPattern }, resolve);
      });
    }, "http://localhost:*/*") as { success: boolean; tabId?: number; error?: string };

    if (!findResult?.success) {
      throw new Error(`Could not find test tab: ${findResult?.error}`);
    }

    const connectResult = await bgPage.evaluate(async ({ tabId, wsPort }: { tabId: number; wsPort: number }) => {
      return new Promise((resolve) => {
        chrome.runtime.sendMessage({ type: "connect-tab", tabId, wsPort }, resolve);
      });
    }, { tabId: findResult.tabId!, wsPort: WS_PORT }) as { success: boolean; error?: string };

    if (!connectResult?.success) {
      throw new Error(`Connection failed: ${connectResult?.error}`);
    }

    await bgPage.close();
    await new Promise((r) => setTimeout(r, 2000));
  });

  test.afterAll(async () => {
    if (context) await context.close();
  });

  test("simple arithmetic evaluation", async () => {
    const result = await evalInBrowser("(+ 1 2 3)");
    expect(result.success).toBe(true);
    expect(result.values).toContain("6");
  });

  test("string operations", async () => {
    const result = await evalInBrowser('(str "Hello" " " "World")');
    expect(result.success).toBe(true);
    expect(result.values?.some((v) => v.includes("Hello World"))).toBe(true);
  });

  test("DOM access in page context", async () => {
    const result = await evalInBrowser("(.-title js/document)");
    expect(result.success).toBe(true);
    expect(result.values?.some((v) => v.includes("Test Page"))).toBe(true);
  });

  test("multiple forms evaluation", async () => {
    const result = await evalInBrowser("(def x 10) (def y 20) (+ x y)");
    expect(result.success).toBe(true);
    expect(result.values).toContain("30");
  });
});
