"""One line per IDE start, from the report idea.log.perf.stats.file writes: <label> <run> <report.json>."""
import json, os, sys

label, run, path = sys.argv[1:4]
if not os.path.exists(path) or not os.path.getsize(path):
    print(f"{label}\trun {run}\tno report")
    sys.exit(0)
d = json.load(open(path))
total = d.get("totalDuration") or d.get("appComponentCreation")
ours = []


def walk(node):
    if isinstance(node, dict):
        if "devsnip" in json.dumps(node.get("name", "")).lower() and isinstance(node.get("duration"), (int, float)):
            ours.append((node["duration"], node["name"]))
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for value in node:
            walk(value)


walk(d)
print(f"{label}\trun {run}\ttotal {total} ms\tdevsnip items {len(ours)}, {sum(o[0] for o in ours):.1f} ms\t{sorted(ours, reverse=True)[:4]}")
