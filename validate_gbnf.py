"""
Мини-интерпретатор GBNF (подмножество, достаточное для verdict.gbnf).
Цель: проверять грамматику в CI на каждый коммит без сборки llama.cpp
под Android. НЕ замена реальному семплеру llama.cpp — только синтаксис
и соответствие примеров грамматике.

Поддержка: rule ::= alt1 | alt2 ..., последовательности, литералы "...",
ссылки на правила, символьные классы [ \t\n]*, комментарии #.
"""

import re
import sys


class GBNFParseError(Exception):
    pass


def load_grammar(path: str) -> dict:
    """Парсит .gbnf файл в {rule_name: [alternative, ...]}, alternative = [term, ...]."""
    text = open(path, encoding="utf-8").read()
    # убрать комментарии
    lines = [ln for ln in text.splitlines() if not ln.strip().startswith("#")]
    text = "\n".join(lines)

    # склеить многострочные правила (продолжение без "::=" на новой строке)
    rules_raw = {}
    current = None
    buf = []
    for ln in text.splitlines():
        if "::=" in ln:
            if current:
                rules_raw[current] = " ".join(buf).strip()
            name, rest = ln.split("::=", 1)
            current = name.strip()
            buf = [rest.strip()]
        elif ln.strip():
            buf.append(ln.strip())
    if current:
        rules_raw[current] = " ".join(buf).strip()

    grammar = {}
    for name, body in rules_raw.items():
        grammar[name] = _parse_alternatives(body)
    return grammar


def _tokenize(body: str):
    # токены: "литерал", идентификатор, [класс]*, |, (, )
    pattern = re.compile(r'"(?:[^"\\]|\\.)*"|\[[^\]]*\]\*?|\||[A-Za-z][A-Za-z0-9\-]*')
    return pattern.findall(body)


def _parse_alternatives(body: str):
    tokens = _tokenize(body)
    alts, cur = [], []
    for tok in tokens:
        if tok == "|":
            alts.append(cur)
            cur = []
        else:
            cur.append(tok)
    alts.append(cur)
    return alts


def match(grammar: dict, rule: str, s: str, pos: int):
    """Возвращает set() возможных позиций после успешного матча правила rule в s[pos:]."""
    if rule not in grammar:
        raise GBNFParseError(f"неизвестное правило: {rule}")
    results = set()
    for alt in grammar[rule]:
        positions = {pos}
        ok = True
        for term in alt:
            new_positions = set()
            for p in positions:
                new_positions |= _match_term(grammar, term, s, p)
            if not new_positions:
                ok = False
                break
            positions = new_positions
        if ok:
            results |= positions
    return results


def _match_term(grammar: dict, term: str, s: str, pos: int):
    if term.startswith('"'):
        lit = term[1:-1].replace('\\"', '"').replace("\\n", "\n").replace("\\t", "\t")
        if s.startswith(lit, pos):
            return {pos + len(lit)}
        return set()
    if term.startswith("["):
        star = term.endswith("*")
        cls = term[1:-1] if not star else term[1:-2]
        chars = _expand_class(cls)
        if star:
            p = pos
            while p < len(s) and s[p] in chars:
                p += 1
            return {p}  # жадно; для ws этого достаточно
        if pos < len(s) and s[pos] in chars:
            return {pos + 1}
        return set()
    # ссылка на правило
    return match(grammar, term, s, pos)


def _expand_class(cls: str):
    out = set()
    i = 0
    raw = cls.replace("\\t", "\t").replace("\\n", "\n")
    while i < len(raw):
        if i + 2 < len(raw) and raw[i + 1] == "-":
            out.update(chr(c) for c in range(ord(raw[i]), ord(raw[i + 2]) + 1))
            i += 3
        else:
            out.add(raw[i])
            i += 1
    return out


def validates(grammar: dict, root: str, s: str) -> bool:
    ends = match(grammar, root, s, 0)
    return len(s) in ends


if __name__ == "__main__":
    grammar = load_grammar("verdict.gbnf")

    valid_examples = [
        '{"level":"danger","confidence":"0.9","category":"otp_shared_request","reason_code":"asks_otp_code"}',
        '{"level":"none","confidence":"0.0","category":"none","reason_code":"no_signal"}',
        # Грамматика теперь БЕЗ ws -> только компактный JSON без пробелов (см. verdict.gbnf,
        # заметка 2026-07-22). Пример с пробелами '{ "level": ... }' теперь НЕ валиден и
        # намеренно убран — модель такого и не сгенерирует.
        '{"level":"warn","confidence":"0.5","category":"remote_access_install","reason_code":"asks_remote_access_app"}',
    ]
    invalid_examples = [
        '{"level":"critical","confidence":"0.9","category":"otp_shared_request","reason_code":"asks_otp_code"}',  # level вне enum
        '{"level":"danger","confidence":"0.95","category":"otp_shared_request","reason_code":"asks_otp_code"}',   # confidence не дискретизирован
        '{"level":"danger","confidence":"0.9","category":"otp_shared_request","reason_code":"asks_otp_code",}',   # висячая запятая
        '{"level":"danger","confidence":"0.9","category":"otp_shared_request"}',   # нет reason_code
        'Конечно! Вот вердикт: {"level":"danger",...}',  # модель добавила преамбулу — типичный провал prompt-only JSON
    ]

    print("=== ДОЛЖНЫ пройти ===")
    all_ok = True
    for ex in valid_examples:
        ok = validates(grammar, "root", ex)
        print(f"  [{'OK' if ok else 'FAIL'}] {ex[:70]}")
        all_ok &= ok

    print("\n=== ДОЛЖНЫ провалиться (грамматика их и не сгенерирует) ===")
    for ex in invalid_examples:
        ok = validates(grammar, "root", ex)
        print(f"  [{'correctly rejected' if not ok else 'ОШИБКА: прошло!'}] {ex[:70]}")
        all_ok &= (not ok)

    print(f"\n{'ВСЕ ПРОВЕРКИ ПРОШЛИ' if all_ok else 'ЕСТЬ ПРОБЛЕМЫ'}")
    sys.exit(0 if all_ok else 1)
