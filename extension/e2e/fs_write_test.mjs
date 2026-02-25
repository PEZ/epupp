import * as squint_core from 'squint-cljs/core.js';
import { test, expect, chromium } from '@playwright/test';
import * as net from 'net';
import * as path from 'path';
import { http_port, nrepl_port_1, ws_port_1, assert_no_errors_BANG_ } from 'fixtures';
var _BANG_context = squint_core.atom(null);
var sleep = async function (ms) {
return (new Promise((function (resolve) {
return setTimeout(resolve, ms);

})));

};
var get_extension_id = async function (context) {
const workers1 = context.serviceWorkers();
if ((workers1.length > 0)) {
return workers1[0].url().split("/")[2]} else {
const sw2 = (await context.waitForEvent("serviceworker"));
return sw2.url().split("/")[2];
};

};
var send_runtime_message = async function (page, msg_type, data) {
return page.evaluate((function (opts) {
return (new Promise((function (resolve) {
return chrome.runtime.sendMessage(Object.assign(({"type": opts.type}), opts.data), resolve);

})));

}), ({"type": msg_type, "data": (await (async () => {
const or__23228__auto__1 = data;
if (squint_core.truth_(or__23228__auto__1)) {
return or__23228__auto__1} else {
return ({})};

})())}));

};
var eval_in_browser = async function (code) {
return (new Promise((function (resolve) {
const client1 = net.createConnection(({"port": nrepl_port_1, "host": "localhost"}));
const _BANG_response2 = squint_core.atom("");
client1.on("data", (function (data) {
squint_core.swap_BANG_(_BANG_response2, squint_core.str, data.toString());
if (squint_core.truth_(squint_core.deref(_BANG_response2).includes("4:done"))) {
client1.destroy();
const response3 = squint_core.deref(_BANG_response2);
const values4 = squint_core.atom([]);
const value_regex5 = (new RegExp("5:value(\\d+):", "g"));
let match6 = value_regex5.exec(response3);
while(true){
if (squint_core.truth_(match6)) {
const len7 = parseInt(match6[1]);
const start_idx8 = (match6.index + match6[0].length);
const value9 = response3.substring(start_idx8, (start_idx8 + len7));
squint_core.swap_BANG_(values4, squint_core.conj, value9);
let G__10 = value_regex5.exec(response3);
match6 = G__10;
continue;
};break;
}
;
const success11 = squint_core.not((() => {
const or__23228__auto__12 = response3.includes("2:ex");
if (squint_core.truth_(or__23228__auto__12)) {
return or__23228__auto__12} else {
return response3.includes("3:err")};

})());
const error13 = ((success11) ? (null) : ((() => {
const err_match14 = response3.match((new RegExp("3:err(\\d+):")));
if (squint_core.truth_(err_match14)) {
const err_len15 = parseInt(err_match14[1]);
const err_start16 = (err_match14.index + err_match14[0].length);
return response3.substring(err_start16, (err_start16 + err_len15));
} else {
return "Unknown error"};

})()));
return resolve(({"success": success11, "values": squint_core.deref(values4), "error": error13}));
};

}));
client1.on("error", (function (err) {
return resolve(({"success": false, "error": err.message}));

}));
const msg17 = `${"d2:op4:eval4:code"}${code.length??''}${":"}${code??''}e`;
return client1.write(msg17);

})));

};
var wait_for_script_tag = async function (pattern, timeout_ms) {
const start1 = Date.now();
const poll_interval2 = 30;
const check_code3 = `${"(pos? (.-length (js/document.querySelectorAll \"script[src*='"}${pattern??''}${"']\")))"}`;
const check_fn4 = (function check () {
return (new Promise((function (resolve, reject) {
return eval_in_browser(check_code3).then((function (result) {
if (squint_core.truth_((() => {
const and__23248__auto__5 = result.success;
if (squint_core.truth_(and__23248__auto__5)) {
return (squint_core.first(result.values) === "true")} else {
return and__23248__auto__5};

})())) {
return resolve(true)} else {
if (((Date.now() - start1) > timeout_ms)) {
return reject((new Error(`${"Timeout waiting for script: "}${pattern??''}`)))} else {
return sleep(poll_interval2).then((function () {
return resolve(check());

}))}};

})).catch(reject);

})));

});
return (await check_fn4());

};
var setup_browser_BANG_ = async function () {
const extension_path1 = path.resolve("dist/chrome");
const ctx2 = (await chromium.launchPersistentContext("", ({"headless": false, "args": ["--no-sandbox", "--allow-file-access-from-files", "--enable-features=ExtensionsManifestV3Only", `${"--disable-extensions-except="}${extension_path1??''}`, `${"--load-extension="}${extension_path1??''}`]})));
squint_core.reset_BANG_(_BANG_context, ctx2);
const ext_id3 = (await get_extension_id(ctx2));
const test_page4 = (await ctx2.newPage());
(await test_page4.goto(`${"http://localhost:"}${http_port??''}${"/basic.html"}`));
(await test_page4.waitForLoadState("domcontentloaded"));
const bg_page5 = (await ctx2.newPage());
(await bg_page5.goto(`${"chrome-extension://"}${ext_id3??''}${"/popup.html"}`, ({"waitUntil": "networkidle"})));
const find_result6 = (await send_runtime_message(bg_page5, "e2e/find-tab-id", ({"urlPattern": "http://localhost:*/*"})));
if (squint_core.truth_((await (async () => {
const and__23248__auto__7 = find_result6;
if (squint_core.truth_(and__23248__auto__7)) {
return find_result6.success} else {
return and__23248__auto__7};

})()))) {
} else {
throw (new Error(`${"Could not find test tab: "}${find_result6.error??''}`))};
const connect_result8 = (await send_runtime_message(bg_page5, "connect-tab", ({"tabId": find_result6.tabId, "wsPort": ws_port_1})));
if (squint_core.truth_((await (async () => {
const and__23248__auto__9 = connect_result8;
if (squint_core.truth_(and__23248__auto__9)) {
return connect_result8.success} else {
return and__23248__auto__9};

})()))) {
} else {
throw (new Error(`${"Connection failed: "}${connect_result8.error??''}`))};
(await bg_page5.close());
return (await wait_for_script_tag("scittle", 5000));

};
test.describe("REPL File System - Write Operations", (function () {
test.beforeAll((function () {
return setup_browser_BANG_();

}));
test.afterAll((function () {
if (squint_core.truth_(squint_core.deref(_BANG_context))) {
return squint_core.deref(_BANG_context).close();
};

}));
test("epupp.fs/save! creates new script from code with manifest", (async function () {
const fn_check1 = (await eval_in_browser("(fn? epupp.fs/save!)"));
expect(fn_check1.success).toBe(true);
expect(fn_check1.values).toContain("true");
const test_code2 = "{:epupp/script-name \"test-script-from-repl\"\n                                       :epupp/site-match \"https://example.com/*\"}\n                                      (ns test-script)\n                                      (js/console.log \"Hello from test script!\")";
const setup_result3 = (await eval_in_browser(`${"(def !save-result (atom :pending))\n                                                         (-> (epupp.fs/save! "}${squint_core.pr_str(test_code2)??''}${")\n                                                             (.then (fn [r] (reset! !save-result r))))\n                                                         :setup-done"}`));
expect(setup_result3.success).toBe(true);
const start4 = Date.now();
const timeout_ms5 = 3000;
while(true){
const check_result6 = (await eval_in_browser("(pr-str @!save-result)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__7 = check_result6.success;
if (squint_core.truth_(and__23248__auto__7)) {
const and__23248__auto__8 = squint_core.seq(check_result6.values);
if (squint_core.truth_(and__23248__auto__8)) {
return !(squint_core.first(check_result6.values) === ":pending")} else {
return and__23248__auto__8};
} else {
return and__23248__auto__7};

})()))) {
const result_str9 = squint_core.first(check_result6.values);
expect(result_str9.includes(":success true")).toBe(true);
expect(result_str9.includes("test_script_from_repl.cljs")).toBe(true)} else {
if (((Date.now() - start4) > timeout_ms5)) {
throw (new Error("Timeout waiting for epupp.fs/save! result"))} else {
(await sleep(50));
continue;
}};break;
}
;
const setup_result10 = (await eval_in_browser("(def !ls-after-save (atom :pending))\n                                                    (-> (epupp.fs/ls)\n                                                        (.then (fn [scripts] (reset! !ls-after-save scripts))))\n                                                    :setup-done"));
expect(setup_result10.success).toBe(true);
const start11 = Date.now();
const timeout_ms12 = 3000;
while(true){
const check_result13 = (await eval_in_browser("(pr-str @!ls-after-save)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__14 = check_result13.success;
if (squint_core.truth_(and__23248__auto__14)) {
const and__23248__auto__15 = squint_core.seq(check_result13.values);
if (squint_core.truth_(and__23248__auto__15)) {
return !(squint_core.first(check_result13.values) === ":pending")} else {
return and__23248__auto__15};
} else {
return and__23248__auto__14};

})()))) {
return expect(squint_core.first(check_result13.values).includes("test_script_from_repl.cljs")).toBe(true)} else {
if (((Date.now() - start11) > timeout_ms12)) {
throw (new Error("Timeout waiting for ls after save"))} else {
(await sleep(50));
continue;
}};
;break;
}
;

}));
test("epupp.fs/save! with {:enabled false} creates disabled script", (async function () {
const test_code16 = "{:epupp/script-name \"disabled-by-default\"\n                                       :epupp/site-match \"https://example.com/*\"}\n                                      (ns disabled-test)\n                                      (js/console.log \"Should be disabled!\")";
const setup_result17 = (await eval_in_browser(`${"(def !save-disabled (atom :pending))\n                                                         (-> (epupp.fs/save! "}${squint_core.pr_str(test_code16)??''}${" {:enabled false})\n                                                             (.then (fn [r] (reset! !save-disabled r))))\n                                                         :setup-done"}`));
expect(setup_result17.success).toBe(true);
const start18 = Date.now();
const timeout_ms19 = 3000;
while(true){
const check_result20 = (await eval_in_browser("(pr-str @!save-disabled)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__21 = check_result20.success;
if (squint_core.truth_(and__23248__auto__21)) {
const and__23248__auto__22 = squint_core.seq(check_result20.values);
if (squint_core.truth_(and__23248__auto__22)) {
return !(squint_core.first(check_result20.values) === ":pending")} else {
return and__23248__auto__22};
} else {
return and__23248__auto__21};

})()))) {
expect(squint_core.first(check_result20.values).includes(":success true")).toBe(true)} else {
if (((Date.now() - start18) > timeout_ms19)) {
throw (new Error("Timeout waiting for save"))} else {
(await sleep(50));
continue;
}};break;
}
;
const setup_result23 = (await eval_in_browser("(def !ls-check-disabled (atom :pending))\n                                                    (-> (epupp.fs/ls)\n                                                        (.then (fn [scripts] (reset! !ls-check-disabled scripts))))\n                                                    :setup-done"));
expect(setup_result23.success).toBe(true);
const start24 = Date.now();
const timeout_ms25 = 3000;
while(true){
const check_result26 = (await eval_in_browser("(pr-str @!ls-check-disabled)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__27 = check_result26.success;
if (squint_core.truth_(and__23248__auto__27)) {
const and__23248__auto__28 = squint_core.seq(check_result26.values);
if (squint_core.truth_(and__23248__auto__28)) {
return !(squint_core.first(check_result26.values) === ":pending")} else {
return and__23248__auto__28};
} else {
return and__23248__auto__27};

})()))) {
const result_str29 = squint_core.first(check_result26.values);
expect(result_str29.includes("disabled_by_default.cljs")).toBe(true);
const scripts_check30 = (await eval_in_browser("(some (fn [s] (and (= (:fs/name s) \"disabled_by_default.cljs\")\n                                                                                   (false? (:fs/enabled s))))\n                                                                     @!ls-check-disabled)"));
expect(scripts_check30.success).toBe(true);
expect(scripts_check30.values).toContain("true")} else {
if (((Date.now() - start24) > timeout_ms25)) {
throw (new Error("Timeout waiting for ls"))} else {
(await sleep(50));
continue;
}};break;
}
;
return (await eval_in_browser("(epupp.fs/rm! \"disabled_by_default.cljs\")"));

}));
test("epupp.fs/save! with vector returns map of per-item results", (async function () {
const code131 = "{:epupp/script-name \"bulk-save-test-1\"\n                                   :epupp/site-match \"https://example.com/*\"}\n                                  (ns bulk-save-1)";
const code232 = "{:epupp/script-name \"bulk-save-test-2\"\n                                   :epupp/site-match \"https://example.com/*\"}\n                                  (ns bulk-save-2)";
const setup_result33 = (await eval_in_browser(`${"(def !bulk-save-result (atom :pending))\n                                                         (-> (epupp.fs/save! ["}${squint_core.pr_str(code131)??''}${" "}${squint_core.pr_str(code232)??''}${"])\n                                                             (.then (fn [result] (reset! !bulk-save-result result))))\n                                                         :setup-done"}`));
expect(setup_result33.success).toBe(true);
const start34 = Date.now();
const timeout_ms35 = 3000;
while(true){
const check_result36 = (await eval_in_browser("(pr-str @!bulk-save-result)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__37 = check_result36.success;
if (squint_core.truth_(and__23248__auto__37)) {
const and__23248__auto__38 = squint_core.seq(check_result36.values);
if (squint_core.truth_(and__23248__auto__38)) {
return !(squint_core.first(check_result36.values) === ":pending")} else {
return and__23248__auto__38};
} else {
return and__23248__auto__37};

})()))) {
const result_str39 = squint_core.first(check_result36.values);
expect(result_str39.includes("0")).toBe(true);
expect(result_str39.includes("1")).toBe(true);
expect(result_str39.includes(":success true")).toBe(true);
expect(result_str39.includes("bulk_save_test_1.cljs")).toBe(true);
expect(result_str39.includes("bulk_save_test_2.cljs")).toBe(true)} else {
if (((Date.now() - start34) > timeout_ms35)) {
throw (new Error("Timeout waiting for bulk save! result"))} else {
(await sleep(50));
continue;
}};break;
}
;
(await eval_in_browser("(-> (js/Promise.all #js [(epupp.fs/rm! \"bulk_save_test_1.cljs\")\n                                                          (epupp.fs/rm! \"bulk_save_test_2.cljs\")])\n                                    (.then (fn [_] :cleaned-up)))"));
return (await sleep(100));

}));
test("epupp.fs/mv! renames a script", (async function () {
const fn_check40 = (await eval_in_browser("(fn? epupp.fs/mv!)"));
expect(fn_check40.success).toBe(true);
expect(fn_check40.values).toContain("true");
const test_code41 = "{:epupp/script-name \"rename-test-original\"\n                                       :epupp/site-match \"https://example.com/*\"}\n                                      (ns rename-test)";
const setup_result42 = (await eval_in_browser(`${"(def !mv-setup (atom :pending))\n                                                         (-> (epupp.fs/save! "}${squint_core.pr_str(test_code41)??''}${")\n                                                             (.then (fn [r] (reset! !mv-setup r))))\n                                                         :setup-done"}`));
expect(setup_result42.success).toBe(true);
const start43 = Date.now();
const timeout_ms44 = 3000;
while(true){
const check_result45 = (await eval_in_browser("(pr-str @!mv-setup)"));
if (squint_core.truth_((await (async () => {
const or__23228__auto__46 = squint_core.not(check_result45.success);
if (or__23228__auto__46) {
return or__23228__auto__46} else {
const or__23228__auto__47 = squint_core.empty_QMARK_(check_result45.values);
if (squint_core.truth_(or__23228__auto__47)) {
return or__23228__auto__47} else {
return (squint_core.first(check_result45.values) === ":pending")};
};

})()))) {
if (((Date.now() - start43) < timeout_ms44)) {
(await sleep(50));
continue;
}};break;
}
;
const setup_result48 = (await eval_in_browser("(def !mv-result (atom :pending))\n                                                    (-> (epupp.fs/mv! \"rename_test_original.cljs\" \"renamed_script.cljs\")\n                                                        (.then (fn [r] (reset! !mv-result r))))\n                                                    :setup-done"));
expect(setup_result48.success).toBe(true);
const start49 = Date.now();
const timeout_ms50 = 3000;
while(true){
const check_result51 = (await eval_in_browser("(pr-str @!mv-result)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__52 = check_result51.success;
if (squint_core.truth_(and__23248__auto__52)) {
const and__23248__auto__53 = squint_core.seq(check_result51.values);
if (squint_core.truth_(and__23248__auto__53)) {
return !(squint_core.first(check_result51.values) === ":pending")} else {
return and__23248__auto__53};
} else {
return and__23248__auto__52};

})()))) {
expect(squint_core.first(check_result51.values).includes(":success true")).toBe(true)} else {
if (((Date.now() - start49) > timeout_ms50)) {
throw (new Error("Timeout waiting for epupp.fs/mv! result"))} else {
(await sleep(50));
continue;
}};break;
}
;
const setup_result54 = (await eval_in_browser("(def !ls-after-mv (atom :pending))\n                                                    (-> (epupp.fs/ls)\n                                                        (.then (fn [scripts] (reset! !ls-after-mv scripts))))\n                                                    :setup-done"));
expect(setup_result54.success).toBe(true);
const start55 = Date.now();
const timeout_ms56 = 3000;
while(true){
const check_result57 = (await eval_in_browser("(pr-str @!ls-after-mv)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__58 = check_result57.success;
if (squint_core.truth_(and__23248__auto__58)) {
const and__23248__auto__59 = squint_core.seq(check_result57.values);
if (squint_core.truth_(and__23248__auto__59)) {
return !(squint_core.first(check_result57.values) === ":pending")} else {
return and__23248__auto__59};
} else {
return and__23248__auto__58};

})()))) {
const result_str60 = squint_core.first(check_result57.values);
expect(result_str60.includes("renamed_script.cljs")).toBe(true);
return expect(result_str60.includes("rename_test_original.cljs")).toBe(false);
} else {
if (((Date.now() - start55) > timeout_ms56)) {
throw (new Error("Timeout waiting for ls after mv"))} else {
(await sleep(50));
continue;
}};
;break;
}
;

}));
test("epupp.fs/mv! with {:confirm false} returns result with :fs/from-name and :fs/to-name", (async function () {
const test_code61 = "{:epupp/script-name \"confirm-test-mv\"\n                                       :epupp/site-match \"https://example.com/*\"}\n                                      (ns confirm-test)";
const setup_result62 = (await eval_in_browser(`${"(def !confirm-mv-setup (atom :pending))\n                                                         (-> (epupp.fs/save! "}${squint_core.pr_str(test_code61)??''}${")\n                                                             (.then (fn [r] (reset! !confirm-mv-setup r))))\n                                                         :setup-done"}`));
expect(setup_result62.success).toBe(true);
const start63 = Date.now();
const timeout_ms64 = 3000;
while(true){
const check_result65 = (await eval_in_browser("(pr-str @!confirm-mv-setup)"));
if (squint_core.truth_((await (async () => {
const or__23228__auto__66 = squint_core.not(check_result65.success);
if (or__23228__auto__66) {
return or__23228__auto__66} else {
const or__23228__auto__67 = squint_core.empty_QMARK_(check_result65.values);
if (squint_core.truth_(or__23228__auto__67)) {
return or__23228__auto__67} else {
return (squint_core.first(check_result65.values) === ":pending")};
};

})()))) {
if (((Date.now() - start63) < timeout_ms64)) {
(await sleep(50));
continue;
}};break;
}
;
const setup_result68 = (await eval_in_browser("(def !confirm-mv-result (atom :pending))\n                                                    (-> (epupp.fs/mv! \"confirm_test_mv.cljs\" \"mv_renamed.cljs\" {:confirm false})\n                                                        (.then (fn [r] (reset! !confirm-mv-result r))))\n                                                    :setup-done"));
expect(setup_result68.success).toBe(true);
const start69 = Date.now();
const timeout_ms70 = 3000;
while(true){
const check_result71 = (await eval_in_browser("(pr-str @!confirm-mv-result)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__72 = check_result71.success;
if (squint_core.truth_(and__23248__auto__72)) {
const and__23248__auto__73 = squint_core.seq(check_result71.values);
if (squint_core.truth_(and__23248__auto__73)) {
return !(squint_core.first(check_result71.values) === ":pending")} else {
return and__23248__auto__73};
} else {
return and__23248__auto__72};

})()))) {
const result_str74 = squint_core.first(check_result71.values);
expect(result_str74.includes(":success true")).toBe(true);
expect(result_str74.includes(":from-name")).toBe(true);
expect(result_str74.includes("confirm_test_mv.cljs")).toBe(true);
expect(result_str74.includes(":to-name")).toBe(true);
expect(result_str74.includes("mv_renamed.cljs")).toBe(true)} else {
if (((Date.now() - start69) > timeout_ms70)) {
throw (new Error("Timeout waiting for mv! result"))} else {
(await sleep(50));
continue;
}};break;
}
;
(await eval_in_browser("(epupp.fs/rm! \"mv_renamed.cljs\")"));
return (await sleep(100));

}));
test("epupp.fs/rm! deletes a script", (async function () {
const fn_check75 = (await eval_in_browser("(fn? epupp.fs/rm!)"));
expect(fn_check75.success).toBe(true);
expect(fn_check75.values).toContain("true");
const test_code76 = "{:epupp/script-name \"delete-test-script\"\n                                       :epupp/site-match \"https://example.com/*\"}\n                                      (ns delete-test)";
const setup_result77 = (await eval_in_browser(`${"(def !rm-setup (atom :pending))\n                                                         (-> (epupp.fs/save! "}${squint_core.pr_str(test_code76)??''}${")\n                                                             (.then (fn [r] (reset! !rm-setup r))))\n                                                         :setup-done"}`));
expect(setup_result77.success).toBe(true);
const start78 = Date.now();
const timeout_ms79 = 3000;
while(true){
const check_result80 = (await eval_in_browser("(pr-str @!rm-setup)"));
if (squint_core.truth_((await (async () => {
const or__23228__auto__81 = squint_core.not(check_result80.success);
if (or__23228__auto__81) {
return or__23228__auto__81} else {
const or__23228__auto__82 = squint_core.empty_QMARK_(check_result80.values);
if (squint_core.truth_(or__23228__auto__82)) {
return or__23228__auto__82} else {
return (squint_core.first(check_result80.values) === ":pending")};
};

})()))) {
if (((Date.now() - start78) < timeout_ms79)) {
(await sleep(50));
continue;
}};break;
}
;
const setup_result83 = (await eval_in_browser("(def !ls-before-rm (atom :pending))\n                                                    (-> (epupp.fs/ls)\n                                                        (.then (fn [scripts] (reset! !ls-before-rm scripts))))\n                                                    :setup-done"));
expect(setup_result83.success).toBe(true);
const start84 = Date.now();
const timeout_ms85 = 3000;
while(true){
const check_result86 = (await eval_in_browser("(pr-str @!ls-before-rm)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__87 = check_result86.success;
if (squint_core.truth_(and__23248__auto__87)) {
const and__23248__auto__88 = squint_core.seq(check_result86.values);
if (squint_core.truth_(and__23248__auto__88)) {
return !(squint_core.first(check_result86.values) === ":pending")} else {
return and__23248__auto__88};
} else {
return and__23248__auto__87};

})()))) {
expect(squint_core.first(check_result86.values).includes("delete_test_script.cljs")).toBe(true)} else {
if (((Date.now() - start84) > timeout_ms85)) {
throw (new Error("Timeout waiting for ls before rm"))} else {
(await sleep(50));
continue;
}};break;
}
;
const setup_result89 = (await eval_in_browser("(def !rm-result (atom :pending))\n                                                    (-> (epupp.fs/rm! \"delete_test_script.cljs\")\n                                                        (.then (fn [r] (reset! !rm-result r))))\n                                                    :setup-done"));
expect(setup_result89.success).toBe(true);
const start90 = Date.now();
const timeout_ms91 = 3000;
while(true){
const check_result92 = (await eval_in_browser("(pr-str @!rm-result)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__93 = check_result92.success;
if (squint_core.truth_(and__23248__auto__93)) {
const and__23248__auto__94 = squint_core.seq(check_result92.values);
if (squint_core.truth_(and__23248__auto__94)) {
return !(squint_core.first(check_result92.values) === ":pending")} else {
return and__23248__auto__94};
} else {
return and__23248__auto__93};

})()))) {
expect(squint_core.first(check_result92.values).includes(":success true")).toBe(true)} else {
if (((Date.now() - start90) > timeout_ms91)) {
throw (new Error("Timeout waiting for epupp.fs/rm! result"))} else {
(await sleep(50));
continue;
}};break;
}
;
const setup_result95 = (await eval_in_browser("(def !ls-after-rm (atom :pending))\n                                                    (-> (epupp.fs/ls)\n                                                        (.then (fn [scripts] (reset! !ls-after-rm scripts))))\n                                                    :setup-done"));
expect(setup_result95.success).toBe(true);
const start96 = Date.now();
const timeout_ms97 = 3000;
while(true){
const check_result98 = (await eval_in_browser("(pr-str @!ls-after-rm)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__99 = check_result98.success;
if (squint_core.truth_(and__23248__auto__99)) {
const and__23248__auto__100 = squint_core.seq(check_result98.values);
if (squint_core.truth_(and__23248__auto__100)) {
return !(squint_core.first(check_result98.values) === ":pending")} else {
return and__23248__auto__100};
} else {
return and__23248__auto__99};

})()))) {
return expect(squint_core.first(check_result98.values).includes("delete_test_script.cljs")).toBe(false)} else {
if (((Date.now() - start96) > timeout_ms97)) {
throw (new Error("Timeout waiting for ls after rm"))} else {
(await sleep(50));
continue;
}};
;break;
}
;

}));
test("epupp.fs/rm! rejects deleting built-in scripts", (async function () {
const setup_result101 = (await eval_in_browser("(def !rm-builtin-result (atom :pending))\n                                                    (-> (epupp.fs/rm! \"GitHub Gist Installer (Built-in)\")\n                                                        (.then (fn [r] (reset! !rm-builtin-result r))))\n                                                    :setup-done"));
expect(setup_result101.success).toBe(true);
const start102 = Date.now();
const timeout_ms103 = 3000;
while(true){
const check_result104 = (await eval_in_browser("(pr-str @!rm-builtin-result)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__105 = check_result104.success;
if (squint_core.truth_(and__23248__auto__105)) {
const and__23248__auto__106 = squint_core.seq(check_result104.values);
if (squint_core.truth_(and__23248__auto__106)) {
return !(squint_core.first(check_result104.values) === ":pending")} else {
return and__23248__auto__106};
} else {
return and__23248__auto__105};

})()))) {
const result_str107 = squint_core.first(check_result104.values);
expect(result_str107.includes(":success false")).toBe(true);
return expect(result_str107.includes("built-in")).toBe(true);
} else {
if (((Date.now() - start102) > timeout_ms103)) {
throw (new Error("Timeout waiting for epupp.fs/rm! built-in result"))} else {
(await sleep(50));
continue;
}};
;break;
}
;

}));
test("epupp.fs/rm! with vector returns map of per-item results", (async function () {
const code1108 = "{:epupp/script-name \"bulk-rm-test-1\"\n                                   :epupp/site-match \"https://example.com/*\"}\n                                  (ns bulk-rm-1)";
const code2109 = "{:epupp/script-name \"bulk-rm-test-2\"\n                                   :epupp/site-match \"https://example.com/*\"}\n                                  (ns bulk-rm-2)";
const setup_result110 = (await eval_in_browser(`${"(-> (js/Promise.all #js [(epupp.fs/save! "}${squint_core.pr_str(code1108)??''}${")\n                                                                                  (epupp.fs/save! "}${squint_core.pr_str(code2109)??''}${")])\n                                                           (.then (fn [_] :done)))"}`));
expect(setup_result110.success).toBe(true);
(await sleep(100));
const setup_result111 = (await eval_in_browser("(def !bulk-rm-result (atom :pending))\n                                                    (-> (epupp.fs/rm! [\"bulk_rm_test_1.cljs\" \"bulk_rm_test_2.cljs\" \"does-not-exist.cljs\"])\n                                                        (.then (fn [result] (reset! !bulk-rm-result result))))\n                                                    :setup-done"));
expect(setup_result111.success).toBe(true);
const start112 = Date.now();
const timeout_ms113 = 3000;
while(true){
const check_result114 = (await eval_in_browser("(pr-str @!bulk-rm-result)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__115 = check_result114.success;
if (squint_core.truth_(and__23248__auto__115)) {
const and__23248__auto__116 = squint_core.seq(check_result114.values);
if (squint_core.truth_(and__23248__auto__116)) {
return !(squint_core.first(check_result114.values) === ":pending")} else {
return and__23248__auto__116};
} else {
return and__23248__auto__115};

})()))) {
const result_str117 = squint_core.first(check_result114.values);
expect(result_str117.includes("bulk_rm_test_1.cljs")).toBe(true);
expect(result_str117.includes("bulk_rm_test_2.cljs")).toBe(true);
expect(result_str117.includes(":success true")).toBe(true);
return expect(result_str117.includes("does-not-exist.cljs")).toBe(true);
} else {
if (((Date.now() - start112) > timeout_ms113)) {
throw (new Error("Timeout waiting for bulk rm! result"))} else {
(await sleep(50));
continue;
}};
;break;
}
;

}));
test("epupp.fs/rm! with {:confirm false} returns result with :fs/name", (async function () {
const test_code118 = "{:epupp/script-name \"confirm-test-rm\"\n                                       :epupp/site-match \"https://example.com/*\"}\n                                      (ns confirm-test)";
const setup_result119 = (await eval_in_browser(`${"(def !confirm-rm-setup (atom :pending))\n                                                         (-> (epupp.fs/save! "}${squint_core.pr_str(test_code118)??''}${")\n                                                             (.then (fn [r] (reset! !confirm-rm-setup r))))\n                                                         :setup-done"}`));
expect(setup_result119.success).toBe(true);
const start120 = Date.now();
const timeout_ms121 = 3000;
while(true){
const check_result122 = (await eval_in_browser("(pr-str @!confirm-rm-setup)"));
if (squint_core.truth_((await (async () => {
const or__23228__auto__123 = squint_core.not(check_result122.success);
if (or__23228__auto__123) {
return or__23228__auto__123} else {
const or__23228__auto__124 = squint_core.empty_QMARK_(check_result122.values);
if (squint_core.truth_(or__23228__auto__124)) {
return or__23228__auto__124} else {
return (squint_core.first(check_result122.values) === ":pending")};
};

})()))) {
if (((Date.now() - start120) < timeout_ms121)) {
(await sleep(50));
continue;
}};break;
}
;
const setup_result125 = (await eval_in_browser("(def !confirm-rm-result (atom :pending))\n                                                    (-> (epupp.fs/rm! \"confirm_test_rm.cljs\" {:confirm false})\n                                                        (.then (fn [r] (reset! !confirm-rm-result r))))\n                                                    :setup-done"));
expect(setup_result125.success).toBe(true);
const start126 = Date.now();
const timeout_ms127 = 3000;
while(true){
const check_result128 = (await eval_in_browser("(pr-str @!confirm-rm-result)"));
if (squint_core.truth_((await (async () => {
const and__23248__auto__129 = check_result128.success;
if (squint_core.truth_(and__23248__auto__129)) {
const and__23248__auto__130 = squint_core.seq(check_result128.values);
if (squint_core.truth_(and__23248__auto__130)) {
return !(squint_core.first(check_result128.values) === ":pending")} else {
return and__23248__auto__130};
} else {
return and__23248__auto__129};

})()))) {
const result_str131 = squint_core.first(check_result128.values);
expect(result_str131.includes(":success true")).toBe(true);
expect(result_str131.includes(":name")).toBe(true);
return expect(result_str131.includes("confirm_test_rm.cljs")).toBe(true);
} else {
if (((Date.now() - start126) > timeout_ms127)) {
throw (new Error("Timeout waiting for rm! result"))} else {
(await sleep(50));
continue;
}};
;break;
}
;

}));
return test("no uncaught errors during fs tests", (async function () {
const ext_id132 = (await get_extension_id(squint_core.deref(_BANG_context)));
const popup133 = (await squint_core.deref(_BANG_context).newPage());
(await popup133.goto(`${"chrome-extension://"}${ext_id132??''}${"/popup.html"}`, ({"waitUntil": "networkidle"})));
(await assert_no_errors_BANG_(popup133));
return (await popup133.close());

}));

}));

export { sleep, get_extension_id, send_runtime_message, eval_in_browser, wait_for_script_tag, setup_browser_BANG_ }
