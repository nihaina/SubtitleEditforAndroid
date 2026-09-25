#!/usr/bin/env python3
"""Convert the installed Nagisa 0.3 model to the compact Rust runtime format."""

import argparse
import gzip
import struct
from pathlib import Path

import numpy as np
import nagisa


def write_u32(output, value):
    output.write(struct.pack("<I", int(value)))


def write_string(output, value):
    data = value.encode("utf-8")
    write_u32(output, len(data))
    output.write(data)


def write_mapping(output, mapping):
    items = sorted(mapping.items())
    write_u32(output, len(items))
    for word, index in items:
        write_string(output, word)
        write_u32(output, index)


def write_array(output, array):
    values = np.asarray(array, dtype="<f4", order="C")
    write_u32(output, values.size)
    output.write(values.tobytes(order="C"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    tagger = nagisa.tagger
    model = tagger._model
    fwd, bwd = model.ws_model.layers[0]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as output:
            output.write(b"NAGISA01")
            dimensions = (
                3,
                model.UNI.shape[1],
                model.BI.shape[1],
                model.WORD.shape[1],
                model.CTYPE.shape[1],
                fwd.h_dim * 2,
            )
            for value in dimensions:
                write_u32(output, value)

            write_mapping(output, tagger._uni2id)
            write_mapping(output, tagger._bi2id)
            write_mapping(output, tagger._word2id)

            for array in (
                model.UNI,
                model.BI,
                model.WORD,
                model.CTYPE,
                fwd.WxT,
                fwd.WhT,
                fwd.b,
                bwd.WxT,
                bwd.WhT,
                bwd.b,
                model.w_wsT,
                model.b_ws,
                model.trans_array,
            ):
                write_array(output, array)


if __name__ == "__main__":
    main()
