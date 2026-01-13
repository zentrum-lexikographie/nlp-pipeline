from collections import defaultdict

from dwds_wic_sbert import WiCTransformer
from pytest import fixture
from sklearn.cluster import KMeans


@fixture
def model():
    return WiCTransformer.load()


def test_clustering(model, snapshot):
    sentences = (
        "Ich bringe Dich noch zur <t>Bahn</t>, damit Du rechtzeitig ankommst.",
        "Mit der <t>Bahn/<t> fährt man bequem von Berlin nach Frankfurt.",
        "Kreisend um die Sonne zog die Erde ihre <t>Bahn</t> wie andere Planeten.",
        "Die Bowlingkugel rollte geradewegs die <t>Bahn</t> hinunter.",
    )
    embeddings = model.encode(sentences)
    km = KMeans(n_clusters=3)
    km.fit(embeddings)
    clusters = defaultdict(list)
    for c, sentence in zip(km.labels_, sentences):
        clusters[c].append(sentence)
    assert snapshot == tuple(tuple(c) for c in clusters.values())
