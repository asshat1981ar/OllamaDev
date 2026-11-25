/* @ds-bundle: {"format":4,"namespace":"DevConsole","components":[{"name":"PageHeader"},{"name":"Panel"},{"name":"StatusDot"},{"name":"Button"},{"name":"Pill"},{"name":"Tag"},{"name":"StaleBanner"},{"name":"StateMessage"},{"name":"Skeleton"},{"name":"StatGrid"},{"name":"StatCard"},{"name":"ListRow"},{"name":"FormRow"},{"name":"TextInput"},{"name":"Select"},{"name":"ResultCard"},{"name":"Notice"},{"name":"ActionResult"},{"name":"PageFooter"}]} */
(function () {
  var R = window.React, h = R.createElement;
  function cx() { return Array.prototype.filter.call(arguments, Boolean).join(" "); }
  function pick(p, omit) { var o = {}; for (var k in p) if (omit.indexOf(k) < 0) o[k] = p[k]; return o; }
  var DOT = { idle: "", ok: "ok", warn: "warn", loading: "warn", err: "err", error: "err" };
  function StatusDot(p) { return h("span", { className: cx("dc-dot", DOT[p.status || "idle"]), role: p.label ? "img" : undefined, "aria-label": p.label, "aria-hidden": p.label ? undefined : true }); }
  function PageHeader(p) {
    return h("header", { className: "dc-header" }, h("div", null,
      p.eyebrow ? h("div", { className: "dc-eyebrow" }, p.eyebrow) : null,
      h("h1", null, p.title, p.accent ? " " : null, p.accent ? h("span", null, p.accent) : null),
      p.description ? h("p", null, p.description) : null), p.children || null);
  }
  function Panel(p) {
    return h("section", { className: cx("dc-panel", p.className) },
      h("div", { className: "dc-panel-head" },
        h("div", { className: "dc-panel-head-left" }, p.status !== undefined ? h(StatusDot, { status: p.status }) : null, h("span", { className: "dc-panel-title" }, p.title)),
        p.freshness ? h("span", { className: "dc-freshness" }, p.freshness) : null, p.actions || null),
      p.banner || null,
      h("div", { className: p.padded ? "dc-pad" : undefined }, p.children));
  }
  function Button(p) { return h("button", Object.assign({ type: "button" }, pick(p, ["variant", "size", "className"]), { className: cx("dc-btn", p.variant === "ghost" && "ghost", p.size === "sm" && "sm", p.className) })); }
  var TONE = { draft: "", ready: "started", started: "started", completed: "ok", done: "ok", canceled: "danger", backlog: "backlog", unstarted: "backlog", triage: "backlog", urgent: "danger", high: "accent", medium: "", low: "", none: "" };
  function Pill(p) { var t = p.tone || TONE[p.state] || ""; return h("span", { className: cx("dc-pill", t) }, p.children); }
  function Tag(p) { return h("span", { className: "dc-tag" }, p.children); }
  function StaleBanner(p) {
    return h("div", { className: "dc-stale", role: "status" }, h("span", null, p.message || "Temporarily unavailable"),
      p.onRetry ? h("button", { type: "button", className: "dc-btn", disabled: !!p.retrying, onClick: p.onRetry }, p.retrying ? "Retrying…" : "Retry") : null);
  }
  function StateMessage(p) { return h("div", { className: cx("dc-state", p.tone === "error" && "err") }, h("strong", null, p.title), p.children); }
  function Skeleton(p) { var rows = p.rows || 1, out = []; for (var i = 0; i < rows; i++) out.push(h("div", { key: i, className: "dc-skeleton", "aria-hidden": true, style: { height: (p.height || 18) + "px", width: p.widths ? p.widths[i % p.widths.length] : undefined } })); return h(R.Fragment, null, out); }
  function StatGrid(p) { return h("div", { className: "dc-stats" }, p.children); }
  function StatCard(p) { return h("div", { className: "dc-stat" }, h("div", { className: "dc-stat-label" }, p.label), h("div", { className: "dc-stat-value" }, p.value == null ? "—" : p.value)); }
  function ListRow(p) {
    var title = p.href ? h("a", { href: p.href, target: "_blank", rel: "noopener" }, p.title) : p.title;
    return h("div", { className: cx("dc-row", p.state) },
      p.id ? h("span", { className: "dc-row-id" }, p.id) : null,
      h("div", { className: "dc-row-title", style: { gridColumn: 1 } }, title),
      p.aside ? h("div", { className: "dc-row-aside" }, p.aside) : null,
      p.meta ? h("div", { className: "dc-row-meta", style: { gridColumn: 1 } }, p.meta) : null);
  }
  function FormRow(p) { return h("form", { className: "dc-form", onSubmit: function (e) { e.preventDefault(); if (p.onSubmit) p.onSubmit(e); } }, p.children); }
  function TextInput(p) { return h("input", Object.assign({ type: "text", autoComplete: "off" }, pick(p, ["className"]), { className: cx("dc-input", p.className) })); }
  function Select(p) { var o = pick(p, ["options", "className"]); return h("select", Object.assign(o, { className: cx("dc-select", p.className) }), (p.options || []).map(function (x) { return h("option", { key: x.value, value: x.value }, x.label); })); }
  function ResultCard(p) {
    return h("div", { className: "dc-card" },
      h("div", { className: "dc-card-name" }, p.href ? h("a", { href: p.href, target: "_blank", rel: "noopener" }, p.name) : p.name),
      p.stats && p.stats.length ? h("div", { className: "dc-card-stats" }, p.stats.join("  ·  ")) : null,
      p.tags && p.tags.length ? h("div", { className: "dc-card-tags" }, p.tags.map(function (t) { return h(Tag, { key: t }, t); })) : null);
  }
  function Notice(p) { return h("div", { className: cx("dc-notice", p.variant === "plain" && "plain") }, p.children); }
  function ActionResult(p) { return h("span", { className: cx("dc-result", p.ok ? "ok" : "err"), role: "status" }, p.children); }
  function PageFooter(p) { return h("footer", { className: "dc-foot" }, p.children); }
  window.DevConsole = Object.assign(window.DevConsole || {}, { PageHeader: PageHeader, Panel: Panel, StatusDot: StatusDot, Button: Button, Pill: Pill, Tag: Tag, StaleBanner: StaleBanner, StateMessage: StateMessage, Skeleton: Skeleton, StatGrid: StatGrid, StatCard: StatCard, ListRow: ListRow, FormRow: FormRow, TextInput: TextInput, Select: Select, ResultCard: ResultCard, Notice: Notice, ActionResult: ActionResult, PageFooter: PageFooter });
})();
