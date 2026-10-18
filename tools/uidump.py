"""Dump text/clickable nodes from a uiautomator XML (read-only helper)."""
import html,re,sys
p=sys.argv[1]
xml=open(p,encoding='utf-8',errors='replace').read()
print("nodes:",xml.count('<node'))
for m in re.finditer(r'<node[^>]*?text="([^"]*)"[^>]*?class="([^"]*)"[^>]*?bounds="([^"]*)"',xml):
    t=html.unescape(m.group(1)); c=m.group(2).split('.')[-1]
    if t.strip(): print(f"{m.group(3):24s} {c:12s} {t}")
