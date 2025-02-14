def look_ahead(iterable):
    el = None
    for next_el in iterable:
        if el:
            yield (el, next_el)
        el = next_el
    if el:
        yield (el, None)
