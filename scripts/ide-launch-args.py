"""Prints the JVM arguments that start an IDE, from its product-info.json: <info> <root> [extra JVM args]."""
import json, shlex, sys

info, root, extra = sys.argv[1], sys.argv[2], sys.argv[3:]
d = json.load(open(info))
launch = next(l for l in d["launch"] if l["os"].lower() in ("macos", "linux", "windows"))
cp = ":".join(f"{root}/lib/{j}" for j in launch["bootClassPathJarNames"])
args = [a.replace("$APP_PACKAGE/Contents", root).replace("$APP_PACKAGE", root).replace("$IDE_HOME", root) for a in launch.get("additionalJvmArguments", [])]
vm = [l.strip() for l in open(f"{root}/bin/{launch['vmOptionsFilePath'].split('/')[-1]}") if l.strip() and not l.startswith("#")]
print(" ".join(shlex.quote(x) for x in vm + args + extra + ["-cp", cp, launch.get("mainClass") or d.get("mainClass")]))
