import argparse

import conllu
from tqdm import tqdm

from ..conllu import serialize
from . import create_pipe

arg_parser = argparse.ArgumentParser(description="Add linguistic annotations")
arg_parser.add_argument(
    "-b",
    "--batch-size",
    help="# of sentences to process in one batch (128 by default)",
    type=int,
)
arg_parser.add_argument(
    "-g", "--gpu", help="IDs of GPUs to use (default: none)", type=int, action="append"
)
arg_parser.add_argument(
    "-i",
    "--input-file",
    help="input CoNLL-U file to annotate",
    type=argparse.FileType("r"),
    default="-",
)
arg_parser.add_argument(
    "-o",
    "--output-file",
    help="output CoNLL-U file with (updated) annotations",
    type=argparse.FileType("w"),
    default="-",
)
arg_parser.add_argument(
    "-p",
    "--parallel",
    help="# of parallel annotation pipelines (1 by default)",
    type=int,
    default="-1",
)
arg_parser.add_argument("--progress", help="Show progress", action="store_true")


args = arg_parser.parse_args()

gpus = args.gpu
batch_size = args.batch_size or 128
n_procs = args.parallel or -1
pipe = create_pipe(gpus=gpus, batch_size=batch_size, n_procs=n_procs)

progress = None
if args.progress:
    progress = tqdm(
        desc="Add linguistic annotations",
        unit=" token",
        unit_scale=True,
        smoothing=0.01,
    )

for s in pipe(conllu.parse_incr(args.input_file)):
    args.output_file.write(serialize(s))
    if progress is not None:
        progress.update(len(s))
