lines = open("C:/Users/junyo/AndroidStudioProjects/KeplerNightLab/app/src/main/java/com/projectnuke/keplernightlab/RawFusionCapture.kt", encoding="utf-8").read().split(chr(10))
for i in range(len(lines)):
    stripped = lines[i].strip()
    if stripped in ("partial = false,", "partial = true,"):
        if i + 1 < len(lines) and lines[i + 1].strip() == ")":
            lines[i] = lines[i].rstrip(",")
open("C:/Users/junyo/AndroidStudioProjects/KeplerNightLab/app/src/main/java/com/projectnuke/keplernightlab/RawFusionCapture.kt", "w", encoding="utf-8").write(chr(10).join(lines))
print("fixed trailing commas")
