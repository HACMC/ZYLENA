#!/usr/bin/env python3
"""Checks an emulator run of ZYLENA on the practice lesson (logcat from run-phone-test.sh).

The practice lesson logs where every paragraph is on screen; ZYLENA (in test mode) logs which line it
is reading and where its outline is. For every stretch of reading this checks, shortly after it
starts and just before it ends, that the paragraph is fully visible below the title bar (or, for a
paragraph taller than the screen, that it fills the screen) and that the outline is on it. It also
checks that every paragraph of every page was read, in order, and that each run ended with "the end".
"""
import re
import sys

LINE = re.compile(r'^\s*(\d+\.\d+)\s+\d+\s+\d+\s+[A-Z]\s+(\w+)\s*:\s?(.*)$')


def norm(s):
    return re.sub(r'\s+', ' ', s.replace('​', '').replace('…', '')).strip()


def main(path):
    texts = {}  # (page, i) -> start of the paragraph
    pos = []  # (t, page, bar, bottom, {i: (top, bottom)})
    moving = []  # times the page was reported moving
    zyl = []  # (t, message)
    markers = []  # (t, name)
    for raw in open(path, encoding='utf-8', errors='replace'):
        m = LINE.match(raw.rstrip('\n'))
        if not m:
            continue
        t, tag, msg = float(m.group(1)), m.group(2), m.group(3)
        if tag == 'MOCK':
            if msg.startswith('text '):
                mm = re.match(r'text p=(\d+) i=(\d+) (.*)', msg)
                if mm:
                    texts[(int(mm.group(1)), int(mm.group(2)))] = norm(mm.group(3))
            elif msg.startswith('pos '):
                mm = re.match(r'pos page=(\d+) moving=(\d) bar=(-?\d+) bottom=(-?\d+)(.*)', msg)
                if mm:
                    paras = {}
                    for a, b, c in re.findall(r'(\d+)=(-?\d+):(-?\d+)', mm.group(5)):
                        paras[int(a)] = (int(b), int(c))
                    pos.append((t, int(mm.group(1)), int(mm.group(3)), int(mm.group(4)), paras))
                    if mm.group(2) == '1':
                        moving.append(t)
            elif msg.startswith('scenario'):
                markers.append((t, msg))
        elif tag == 'ZYLENA':
            zyl.append((t, msg))

    def which(snip):
        s = norm(snip)
        for k, v in texts.items():
            n = min(len(v), len(s), 30)
            if n >= 12 and s[:n] == v[:n]:
                return k
        return None

    def pos_at(t):
        best = None
        for p in pos:
            if p[0] <= t:
                best = p
            else:
                break
        return best

    def mark_at(t, since):
        best = None
        for tz, msg in zyl:
            if tz > t:
                break
            if msg.startswith('TEST mark '):
                best = (tz, msg[len('TEST mark '):])
        if best is None or best[0] < since - 30:
            return None
        if best[1] == 'hidden':
            return 'hidden'
        return tuple(int(x) for x in best[1].split(','))

    def settled(ts, limit):
        """The first moment from ts on when the page has been still for 1.5 s: the outline may trail the
        page by a few hundred milliseconds on the slow emulator after a swipe ends."""
        t = ts
        while t < limit:
            recent = [m for m in moving if t - 1.5 < m <= t]
            if not recent:
                return t
            t = max(recent) + 1.55
        return limit

    fails, oks = [], 0
    report = []
    reads = []  # (t, page, i)
    ends = ('TEST reading line', 'finished reading', 'button: stop', 'whole page: scanning')
    for k, (t0, msg) in enumerate(zyl):
        if not msg.startswith('TEST reading line'):
            continue
        snip = msg.split(': ', 1)[1] if ': ' in msg else ''
        para = which(snip)
        t1 = None
        for t, m2 in zyl[k + 1:]:
            if m2.startswith(ends):
                t1 = t
                break
        if t1 is None:
            t1 = t0 + 3
        if para is None:
            report.append(f'      {t0:.1f} heading or unknown line: {snip[:50]}')
            continue
        reads.append((t0, para[0], para[1]))
        samples = []
        if t1 - t0 > 3.2:
            samples.append(('soon after start', t0 + 2.6))
        if t1 - t0 > 0.7:
            samples.append(('before the end', t1 - 0.25))
        for label, ts in samples:
            ts = settled(ts, max(ts, t1 - 0.25))
            p = pos_at(ts)
            if p is None:
                fails.append(f'{ts:.1f} p{para[0]}#{para[1]}: no position known')
                continue
            _, page, bar, limit, paras = p
            where = f'p{para[0]}#{para[1]} {label}'
            if page != para[0]:
                fails.append(f'{ts:.1f} {where}: the screen shows page {page}')
                continue
            if para[1] not in paras:
                fails.append(f'{ts:.1f} {where}: position not reported')
                continue
            top, bottom = paras[para[1]]
            room = limit - bar
            if bottom - top <= room:
                ok = top >= bar - 3 and bottom <= limit + 3
                what = f'top {top} bottom {bottom}, visible {bar}..{limit}'
            else:
                shown = min(bottom, limit) - max(top, bar)
                ok = shown >= 0.8 * room
                what = f'tall ({bottom - top} px) shows {shown} of {room} px'
            mk = mark_at(ts, t0)
            mark_ok = True
            if mk is None or mk == 'hidden':
                mark_ok = False
                what += ', no outline'
            else:
                exp_top, exp_bottom = max(top, bar), min(bottom, limit)
                # Right after a move the outline may trail the page for a moment; by the end it must be exact.
                tol = (6 if label == 'before the end' else 30) if ok and bottom - top <= room else 40
                if abs(mk[1] - exp_top) > tol or abs(mk[3] - exp_bottom) > tol:
                    mark_ok = False
                what += f', outline {mk[1]}..{mk[3]}'
            if ok and mark_ok:
                oks += 1
                report.append(f'  ok  {ts:.1f} {where}: {what}')
            else:
                fails.append(f'{ts:.1f} {where}: {what}' + ('' if ok else ' [NOT FULLY VISIBLE]') + ('' if mark_ok else ' [OUTLINE WRONG]'))
                report.append(f'FAIL  {ts:.1f} {where}: {what}')

    # Whole-run checks, per scenario.
    bounds = [t for t, _ in markers] + [float('inf')]
    for n, (ts, name) in enumerate(markers):
        te = bounds[n + 1]
        part = [(t, p, i) for t, p, i in reads if ts <= t < te]
        zpart = [m for t, m in zyl if ts <= t < te]
        seq = []
        for _, p, i in part:
            if not seq or seq[-1] != (p, i):
                seq.append((p, i))
        report.append(f'{name}: read ' + ' '.join(f'p{p}#{i}' for p, i in seq))
        report.append(f'{name}: scans {sum(m.startswith("whole page: scanning") for m in zpart)}, '
                      f'already scanned {sum("scanned already" in m for m in zpart)}, '
                      f'moves {sum(m.startswith("showing the whole paragraph") for m in zpart)}')
        if not any(m.startswith('whole page: the end') for m in zpart) and 'stop' not in name:
            fails.append(f'{name}: never reached "the end"')
        for a, b in zip(seq, seq[1:]):
            if b < a and 'resume' not in name:
                fails.append(f'{name}: read p{b[0]}#{b[1]} after p{a[0]}#{a[1]} (out of order)')
        if name == 'scenario 1':
            missing = [k for k in sorted(texts) if k not in set(seq)]
            if missing:
                fails.append(f'{name}: paragraphs never read: ' + ' '.join(f'p{p}#{i}' for p, i in missing))
        if name == 'scenario 2 resume':
            if not any('scanned already' in m for m in zpart):
                fails.append(f'{name}: scanned the page again instead of going on')
            before = [x for x in reads if markers[n - 1][0] <= x[0] < ts]
            if before and seq and seq[0] != (before[-1][1], before[-1][2]):
                fails.append(f'{name}: went on at p{seq[0][0]}#{seq[0][1]}, not where it stopped '
                             f'(p{before[-1][1]}#{before[-1][2]})')
            later = set(seq) | {(p, i) for _, p, i in before}
            missing = [k for k in sorted(texts) if k[0] >= 1 and k not in later]
            if missing:
                fails.append(f'{name}: paragraphs never read: ' + ' '.join(f'p{p}#{i}' for p, i in missing))

    print('\n'.join(report))
    print()
    print(f'{oks} checks passed, {len(fails)} failed')
    for f in fails:
        print('FAILED: ' + f)
    print()
    fail_times = [float(f.split(' ', 1)[0]) for f in fails if re.match(r'^\d+\.\d', f)]

    def fails_near(t):
        return any(ft - 6 <= t <= ft + 1 for ft in fail_times)

    print('ZYLENA log (scan and scroll lines):')
    for t, m in zyl:
        if m.startswith(('whole page', 'showing the whole', 'next page', 'button', 'outline offset', 'speak ', 'finished',
                         'TEST screen', 'TEST page area', 'TEST place', 'TEST reading line')) or (m.startswith('TEST paragraph') and fails_near(t)):
            print(f'  {t:.1f} {m}')
    return 1 if fails or not reads else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
