from collections import defaultdict

from pytest import mark

wic_tf_available = True
try:
    from dwds_wic_sbert import WiCTransformer
except ImportError:
    wic_tf_available = False


@mark.skipif(not wic_tf_available, reason="DWDS-WiC-SBERT not installed")
def test_clustering(snapshot):
    model = WiCTransformer.load()
    sentences = (
        "Ich bringe Dich noch zur <t>Bahn</t>, damit Du rechtzeitig ankommst.",
        "Mit der <t>Bahn/<t> fährt man bequem von Berlin nach Frankfurt.",
        "Kreisend um die Sonne zog die Erde ihre <t>Bahn</t> wie andere Planeten.",
        "Die Bowlingkugel rollte geradewegs die <t>Bahn</t> hinunter.",
    )
    embeddings = model.encode(sentences)

    from sklearn.cluster import KMeans

    km = KMeans(n_clusters=3)
    km.fit(embeddings)
    clusters = defaultdict(list)
    for c, sentence in zip(km.labels_, sentences):
        clusters[c].append(sentence)
    assert snapshot == tuple(tuple(c) for c in clusters.values())
