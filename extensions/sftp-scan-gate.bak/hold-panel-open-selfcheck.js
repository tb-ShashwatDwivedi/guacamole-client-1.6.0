#!/usr/bin/env node
'use strict';

function sftpScanResolveHoldPanelOpen(panelOpen, entryCount) {
    if (!entryCount || entryCount < 1)
        return false;
    return !!panelOpen;
}

function simulateRegister(state, eventId, isNewEvent) {
    if (isNewEvent)
        state.panelOpen = true;
    if (!state.active[eventId])
        state.active[eventId] = { eventId: eventId, phase: 'POLLING' };
    var entries = Object.keys(state.active).filter(function(id) {
        var e = state.active[id];
        return e && e.phase !== 'READY' && e.phase !== 'REJECTED';
    });
    if (!entries.length)
        state.panelOpen = false;
    return sftpScanResolveHoldPanelOpen(state.panelOpen, entries.length);
}

function assert(name, cond) {
    if (!cond) {
        console.error('FAIL:', name);
        process.exitCode = 1;
        return;
    }
    console.log('OK:', name);
}

assert('empty clears panel', sftpScanResolveHoldPanelOpen(false, 0) === false);

var s1 = { active: {}, panelOpen: false };
assert('first hold auto-opens', simulateRegister(s1, '1', true) === true);

var s2 = { active: { '1': { phase: 'POLLING' } }, panelOpen: true };
s2.panelOpen = false;
assert('poll update same file stays closed', simulateRegister(s2, '1', false) === false);

var s3 = { active: { '1': { phase: 'POLLING' } }, panelOpen: false };
assert('second file re-opens', simulateRegister(s3, '2', true) === true);

var s4 = { active: {}, panelOpen: false };
simulateRegister(s4, '9', true);
assert('badge count 1 with panel open after first event', s4.panelOpen === true);

if (process.exitCode) {
    console.error('hold-panel-open-selfcheck: failed');
    process.exit(1);
}
console.log('hold-panel-open-selfcheck: all passed');
