#!/usr/bin/env python3
"""Convert build/war/index.jsp (post-Ant-filtered) to a static index.html.

The JSP has these JSP-isms that need stripping for a static deploy:
  - <%@page ... %>  import directives
  - <% ... %>        scriptlet blocks (auth redirect, gatag, locale vars)
  - <%= expr %>      expression substitutions (odeBase, translation)

We do explicit substitutions first so the regex strip below doesn't leave
empty placeholder strings behind.
"""
import os
import re
import sys

if len(sys.argv) != 4:
    sys.stderr.write("usage: generate-index.py <index.jsp> <ode-dir> <out.html>\n")
    sys.exit(2)

jsp_path, ode_dir, out_path = sys.argv[1], sys.argv[2], sys.argv[3]

# English locale hash for blocklyeditor (i18n.java mapping); only en shipped
# in this offline build. To support more locales, replace the substring below.
EN_LOCALE_HASH = "241c8ecf"

aiblockly = next(
    (f for f in os.listdir(ode_dir)
     if f.startswith("aiblockly-") and f.endswith(".cache.js")),
    None,
)
if aiblockly is None:
    sys.stderr.write("no aiblockly-*.cache.js found in " + ode_dir + "\n")
    sys.exit(1)

src = open(jsp_path, encoding="utf-8").read()

# Explicit substitutions first — these would otherwise be eaten by the
# regex strip and leave empty placeholders.
src = src.replace("<%= translation %>", "ode/messages_" + EN_LOCALE_HASH + ".cache.js")
src = src.replace("<%=translation%>", "ode/messages_" + EN_LOCALE_HASH + ".cache.js")
src = src.replace("<%= odeBase %>", "")
src = src.replace("<%=odeBase%>", "")

# Strip all remaining JSP directives / scriptlets / expressions.
# DOTALL so a scriptlet block that spans lines is removed in one go.
out = re.sub(r"<%.*?%>", "", src, flags=re.DOTALL)

# Belt and braces: remove stray empty src="..." attributes left behind.
out = re.sub(r'src=""', "", out)

# Load the generated archive lookup before the GWT application starts. This
# makes packaged template imports work when index.html is opened as file://.
out = out.replace(
    "</head>",
    "    <script type=\"text/javascript\" src=\"templates/template-data.js\"></script>\n  </head>",
    1,
)

with open(out_path, "w", encoding="utf-8") as f:
    f.write(out)

print("wrote " + out_path + " (" + str(len(out)) + " bytes)")