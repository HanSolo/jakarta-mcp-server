#!/usr/bin/env node
// Independent client check for the Tasks extension (and optionally MCP Apps), written against the spec text, not against the server code.
// No dependencies, Node 20 or newer.
//
//   node check.mjs [url]            Tasks checks against mcp-example-tasks (default http://localhost:8080/mcp, server started with MCP_TASKS_ENABLED=true)
//   node check.mjs [url] --apps     MCP Apps checks against mcp-example-apps instead
//
// Every check prints PASS or FAIL with the evidence; the exit code is 1 when any check failed.

const args = process.argv.slice(2);
const URL_ = args.find(a => !a.startsWith('--')) ?? process.env.MCP_URL ?? 'http://localhost:8080/mcp';
const APPS = args.includes('--apps');
const VERSION = '2026-07-28';
const TASKS = 'io.modelcontextprotocol/tasks';
const UI = 'io.modelcontextprotocol/ui';

let failed = 0, nextId = 1;
const ok = (name, detail = '') => console.log(`PASS  ${name}${detail ? '  ' + detail : ''}`);
const bad = (name, detail = '') => { failed++; console.log(`FAIL  ${name}${detail ? '  ' + detail : ''}`); };
const check = (name, cond, detail = '') => (cond ? ok(name) : bad(name, detail));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const short = v => { const s = typeof v === 'string' ? v : JSON.stringify(v); return s.length > 300 ? s.slice(0, 300) + '...' : s; };

function meta(extensions) {
    return { 'io.modelcontextprotocol/protocolVersion': VERSION, 'io.modelcontextprotocol/clientCapabilities': extensions ? { extensions } : {} };
}

function headers(method, name, over = {}) {
    const h = { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'MCP-Protocol-Version': VERSION, 'Mcp-Method': method };
    if (name) h['Mcp-Name'] = name;
    return { ...h, ...over };   // an override wins, so a check can send a wrong Mcp-Name
}

// Reads a reply that is either plain JSON or an event stream; returns the JSON-RPC message with the given id (the last one wins).
async function parse(res, id) {
    const text = await res.text();
    const type = res.headers.get('content-type') ?? '';
    let msg = null;
    if (type.includes('text/event-stream')) {
        for (const block of text.split(/\r?\n\r?\n/)) {
            const data = block.split(/\r?\n/).filter(l => l.startsWith('data:')).map(l => l.slice(5).trimStart()).join('\n');
            if (!data) continue;
            try { const m = JSON.parse(data); if (m.id === id) msg = m; } catch { /* not JSON */ }
        }
    } else if (text.trim()) {
        try { msg = JSON.parse(text); } catch { /* handled below */ }
    }
    return { status: res.status, type, text, msg };
}

async function rpc(method, params = {}, { name, extensions, hdr } = {}) {
    const id = nextId++;
    const body = { jsonrpc: '2.0', id, method, params: { ...params, _meta: meta(extensions || undefined) } };
    const res = await fetch(URL_, { method: 'POST', headers: headers(method, name, hdr), body: JSON.stringify(body) });
    return parse(res, id);
}

const withTasks = { [TASKS]: {} };
const call = (tool, argumentsObj, extensions) => rpc('tools/call', { name: tool, arguments: argumentsObj }, { name: tool, extensions });
const taskOp = (op, taskId, extra = {}, extensions = withTasks, hdr) => rpc(op, { taskId, ...extra }, { name: taskId, extensions, hdr });

async function waitFor(taskId, wanted, seconds = 30) {
    const end = Date.now() + seconds * 1000;
    let last;
    while (Date.now() < end) {
        last = (await taskOp('tasks/get', taskId)).msg?.result;
        if (last && wanted.includes(last.status)) return last;
        await sleep(300);
    }
    return last;
}

const pending = task => !!task?.inputRequests && Object.keys(task.inputRequests).length > 0;
const publish = taskId => taskOp('tasks/update', taskId, { inputResponses: { publish: { action: 'accept', content: { publish: true } } } });

async function tasksChecks() {
    console.log(`Tasks checks against ${URL_}\n`);

    const d = await rpc('server/discover');
    check('server/discover answers 200 with JSON', d.status === 200 && d.msg?.result, `status ${d.status}: ${short(d.text)}`);
    check(`discover lists ${VERSION}`, JSON.stringify(d.msg?.result ?? {}).includes(VERSION), short(d.msg?.result ?? ''));
    check(`discover advertises ${TASKS}`, JSON.stringify(d.msg?.result ?? {}).includes(TASKS), 'is the server running with MCP_TASKS_ENABLED=true?');

    const list = await rpc('tools/list');
    const tool = list.msg?.result?.tools?.find(t => t.name === 'build_report');
    check('tools/list contains build_report', !!tool, short(list.text));
    if (tool) console.log(`      build_report: ${short(tool)}`);

    // 1. a task, polled
    const created = await call('build_report', { topic: 'client check', sections: 2 }, withTasks);
    const task = created.msg?.result;
    check('tools/call with the extension creates a task (taskId, status)', typeof task?.taskId === 'string' && typeof task?.status === 'string', short(created.text));
    if (!task?.taskId) { console.log('\nNo task came back, stopping.'); return; }
    console.log(`      CreateTaskResult: ${short(task)}`);
    check('the task starts working', ['working', 'input_required'].includes(task.status), task.status);

    const asking = await waitFor(task.taskId, ['input_required', 'failed', 'cancelled', 'completed']);
    check('the task reaches input_required', asking?.status === 'input_required', short(asking ?? ''));
    check('input_required carries the pending request (inputRequests)', !!asking?.inputRequests && Object.keys(asking.inputRequests).length > 0, short(asking ?? ''));
    console.log(`      input_required: ${short(asking ?? '')}`);

    const upd = await publish(task.taskId);
    check('tasks/update accepts the answer', !upd.msg?.error, short(upd.text));
    const done = await waitFor(task.taskId, ['completed', 'failed', 'cancelled']);
    check('the task completes after the answer', done?.status === 'completed', short(done ?? ''));
    check('the completed task carries the result, which reflects the answer', JSON.stringify(done?.result ?? {}).includes('published'), short(done ?? ''));
    check('ttl is reported', done && ('ttlMs' in done || 'ttl' in done), short(done ?? ''));

    // 2. cancel
    const second = (await call('build_report', { topic: 'cancel me', sections: 8 }, withTasks)).msg?.result;
    if (second?.taskId) {
        const cancel = await taskOp('tasks/cancel', second.taskId);
        check('tasks/cancel is accepted', !cancel.msg?.error, short(cancel.text));
        const end = await waitFor(second.taskId, ['cancelled', 'completed', 'failed'], 15);
        check('a cancelled task ends as cancelled', end?.status === 'cancelled', short(end ?? ''));
    } else {
        bad('second task for the cancel check', 'no task');
    }

    // 3. errors
    const unknown = await taskOp('tasks/get', 'no-such-task');
    check('an unknown task id is -32602', unknown.msg?.error?.code === -32602, short(unknown.text));
    const noExt = await taskOp('tasks/get', task.taskId, {}, null);
    check('tasks/get without the extension is -32021', noExt.msg?.error?.code === -32021, short(noExt.text));
    const mismatch = await taskOp('tasks/get', task.taskId, {}, withTasks, { 'Mcp-Name': 'something-else' });
    check('a wrong Mcp-Name is -32020', mismatch.msg?.error?.code === -32020, `status ${mismatch.status}: ${short(mismatch.text)}`);

    // 4. fallback for clients without the extension
    const inline = await call('build_report', { topic: 'inline', sections: 1 }, undefined);
    check('without the extension the call runs inline (content, no taskId)', !!inline.msg?.result?.content && !inline.msg.result.taskId, short(inline.text));

    // 5. subscriptions/listen
    await listenCheck();

    console.log(`\n${failed === 0 ? 'All checks passed.' : failed + ' check(s) failed.'}`);
}

// Reads the stream with the Web Streams reader and returns every JSON message of the SSE stream until it closes or the timeout hits.
async function listenCheck() {
    const t = (await call('build_report', { topic: 'listen', sections: 2 }, withTasks)).msg?.result;
    if (!t?.taskId) { bad('task for the listen check', 'no task'); return; }
    const id = nextId++;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 40000);
    const messages = [];
    let answered = false, answeredAfter = -1;
    try {
        const res = await fetch(URL_, {
            method: 'POST', signal: ctrl.signal, headers: headers('subscriptions/listen'),
            body: JSON.stringify({ jsonrpc: '2.0', id, method: 'subscriptions/listen', params: { notifications: { taskIds: [t.taskId] }, _meta: meta(withTasks) } })
        });
        check('subscriptions/listen opens an event stream', (res.headers.get('content-type') ?? '').includes('text/event-stream'), `status ${res.status} ${res.headers.get('content-type')}`);
        const reader = res.body.getReader(), dec = new TextDecoder();
        let buf = '', finished = false;
        while (!finished) {
            const { done, value } = await reader.read();
            if (done) break;
            buf += dec.decode(value, { stream: true });
            let cut;
            while ((cut = buf.search(/\r?\n\r?\n/)) >= 0) {
                const block = buf.slice(0, cut); buf = buf.slice(cut).replace(/^\r?\n\r?\n/, '');
                const data = block.split(/\r?\n/).filter(l => l.startsWith('data:')).map(l => l.slice(5).trimStart()).join('\n');
                if (!data) continue;
                let m; try { m = JSON.parse(data); } catch { continue; }
                messages.push(m);
                const p = m.params ?? {};
                if (m.method === 'notifications/tasks' && p.status === 'input_required' && !answered) { answered = true; answeredAfter = messages.filter(x => x.method === 'notifications/tasks').length; publish(t.taskId); }
                if (m.method === 'notifications/tasks' && ['completed', 'failed', 'cancelled'].includes(p.status)) finished = true;
            }
        }
        ctrl.abort();
    } catch (e) {
        if (e.name !== 'AbortError') bad('listen stream', String(e));
    } finally { clearTimeout(timer); }
    const notes = messages.filter(m => m.method === 'notifications/tasks');
    console.log(`      ${messages.length} message(s) on the stream, ${notes.length} task notification(s): ${notes.map(n => n.params?.status).join(' -> ')}`);
    notes.forEach((n, i) => {
        const q = n.params ?? {};
        console.log(`      #${i + 1} ${q.status}${i >= answeredAfter && answeredAfter >= 0 ? ' (after the answer)' : ''}  statusMessage=${JSON.stringify(q.statusMessage ?? null)}  lastUpdatedAt=${q.lastUpdatedAt}  inputRequests=${pending(q) ? Object.keys(q.inputRequests).join(',') : q.inputRequests ? '{} (empty)' : 'none'}`);
    });
    const stale = notes.filter((n, i) => i >= answeredAfter && answeredAfter >= 0 && n.params?.status === 'input_required');
    if (stale.length > 0) {
        // Not a failure: the spec text decides whether a task that has been answered may still be reported as input_required.
        console.log(`NOTE  ${stale.length} notification(s) after the answer still say input_required${stale.some(n => pending(n.params)) ? ' and still carry open inputRequests (a client could ask again)' : ' with no open inputRequests (the answer was recorded, the work has not resumed yet)'}`);
        console.log(`      ${short(stale[0].params)}`);
    }
    const ack = messages.find(m => m.method === 'notifications/subscriptions/acknowledged' || JSON.stringify(m).includes('acknowledged'));
    check('the stream acknowledges the subscription and lists the task id', !!ack && JSON.stringify(ack).includes(t.taskId), short(messages[0] ?? 'nothing'));
    check('a notification arrives for the task', notes.some(n => n.params?.taskId === t.taskId), short(messages));
    check('the stream shows input_required and then completed', notes.some(n => n.params?.status === 'input_required') && notes.at(-1)?.params?.status === 'completed', notes.map(n => n.params?.status).join(' -> '));
    const doneTask = notes.at(-1)?.params;
    check('the final notification carries the result', JSON.stringify(doneTask?.result ?? {}).includes('published'), short(doneTask ?? ''));
}

async function appsChecks() {
    console.log(`MCP Apps checks against ${URL_}\n`);
    const withUi = { [UI]: { mimeTypes: ['text/html;profile=mcp-app'] } };
    const d = await rpc('server/discover');
    check('server/discover answers', d.status === 200 && d.msg?.result, short(d.text));
    check(`discover advertises ${UI}`, JSON.stringify(d.msg?.result ?? {}).includes(UI), short(d.msg?.result ?? ''));
    const list = await rpc('tools/list');
    const show = list.msg?.result?.tools?.find(t => t.name === 'show_clock');
    check('tools/list contains show_clock with _meta.ui.resourceUri', typeof show?._meta?.ui?.resourceUri === 'string', short(show ?? list.text));
    const uri = show?._meta?.ui?.resourceUri;
    if (uri) {
        const r = await rpc('resources/read', { uri }, { name: uri });
        const c = r.msg?.result?.contents?.[0];
        check('the page can be read', !!c && (c.text ?? '').includes('<'), short(r.text));
        check('the page has the MCP Apps mime type', (c?.mimeType ?? '').startsWith('text/html'), c?.mimeType);
    }
    const plain = await call('show_clock', {}, undefined);
    const rich = await call('show_clock', {}, withUi);
    const t1 = plain.msg?.result?.content?.[0]?.text ?? '', t2 = rich.msg?.result?.content?.[0]?.text ?? '';
    check('the text adapts: apps-capable clients get "Showing the clock"', t2.startsWith('Showing the clock') && !t1.startsWith('Showing the clock'), `plain: ${t1} | apps: ${t2}`);
    console.log(`\n${failed === 0 ? 'All checks passed.' : failed + ' check(s) failed.'}`);
}

try {
    await (APPS ? appsChecks() : tasksChecks());
} catch (e) {
    console.error(`\nThe check could not run: ${e.cause?.code ?? e.message}. Is the server up at ${URL_}?`);
    process.exit(2);
}
process.exit(failed === 0 ? 0 : 1);
