'''Logging configuration.'''
import logging

logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s - %(levelname)-10s - %(name)-10s: %(message)s",
)

logger = logging.getLogger()
