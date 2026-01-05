import json
import re

from dateutil.parser import ParserError as DateParserError
from dateutil.parser import parse as parse_date


def look_ahead(iterable):
    el = None
    for next_el in iterable:
        if el:
            yield (el, next_el)
        el = next_el
    if el:
        yield (el, None)


def norm_str(s):
    s = s.strip() if s else ""
    return s if s else None


def norm_strs(strs):
    strs = (norm_str(s) for s in strs)
    strs = (s for s in strs if s)
    return tuple(strs) if strs else None


def norm_str_set(strs):
    strs = norm_strs(strs)
    return set(strs) if strs else None


def join_strs(sep, strs):
    return sep.join(strs) if strs else None


_puncts = "!,-./:;?[]{}"
_puncts_re = re.compile(f"([{re.escape(_puncts)}])\\.")


def norm_title(s):
    s = norm_str(s)
    return _puncts_re.sub(r"\1", s) if s else None


_tag_split_re = re.compile(r"[:\s]")


def tags(s, split_re=_tag_split_re):
    s = norm_str(s)
    strs = norm_strs(split_re.split(s)) if s else None
    return json.dumps(sorted(strs)) if strs else None


_year_re = re.compile(r"^[12][0-9]{3}$")
_year_month_re = re.compile(r"^[12][0-9]{3}-[01][0-9]$")


def norm_date(s):
    s = norm_str(s)
    if not s:
        return None
    try:
        if _year_re.match(s) or _year_month_re.match(s):
            return None
        else:
            return parse_date(s).date()
    except DateParserError:
        return None


def format_date(d):
    return d.strftime("%d.%m.%Y") if d else None


def norm_year(s):
    try:
        s = norm_str(s)
        return parse_date(s).year if s else None
    except DateParserError:
        return None
