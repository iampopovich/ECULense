"""Convert lennykean/OBDII.DTC DTC.cs (MIT) to the plan's CSV format: "CODE","Description".

Only SAE/ISO generic ranges are kept (P0, P2, P34-P39, B0, B3, C0, C3, U0, U3): manufacturer ranges
share numbers across makes, so a description there would mislead on a Kia.
Double quotes inside descriptions are replaced with single quotes (the app parser does no unescaping).
"""
import re
import sys

src, dst = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
pattern = re.compile(r'Description\("((?:[^"\\]|\\.)*)"\)\]\s*\n\s*([PBCU][0-9A-F]{4}) = ')


def generic(code: str) -> bool:
    letter, d1, d2 = code[0], code[1], code[2]
    if letter == "P":
        return d1 in "02" or (d1 == "3" and d2 in "456789")
    return d1 in "03"


rows = {}
for desc, code in pattern.findall(text):
    if generic(code):
        rows[code] = desc.replace('\\"', "'").strip()

with open(dst, "w", encoding="utf-8", newline="\n") as out:
    for code in sorted(rows):
        out.write(f'"{code}","{rows[code]}"\n')
print(len(rows))
