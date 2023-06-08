import datasketch


def minhash(tokens):
    tokens = map(lambda s: s.encode('utf8'), tokens)
    h, = datasketch.MinHash.bulk((tokens, ))
    h = datasketch.LeanMinHash(h)
    bs = bytearray(h.bytesize())
    h.serialize(bs)
    return bs.hex()
