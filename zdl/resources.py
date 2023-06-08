import os
import pathlib

resources_dir = os.getenv(
    'ZDL_NLP_RESOURCES_DIR',
    (pathlib.Path(__file__) / '..' / '..' / 'resources').resolve()
)


def bucket(name):
    resource_dir = resources_dir / name
    resource_dir.mkdir(parents=True, exist_ok=True)
    return resource_dir.as_posix()
