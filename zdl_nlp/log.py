import logging
import os
import sys

log_level = logging.DEBUG if os.environ.get("DEBUG", "") else logging.INFO

logging.basicConfig(
    level=log_level,
    format="[%(asctime)s] %(levelname)7s – %(name)s : %(message)s",
    handlers=(logging.StreamHandler(stream=sys.stderr),),
)

logger = logging.getLogger("zdl_nlp")
