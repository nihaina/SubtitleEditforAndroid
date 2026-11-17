These small audio fixtures are copied unchanged from the bundled TagLib tests:
`app/src/main/cpp/third_party/taglib/tests/data/`.

- `mac-399.ape`: Monkey's Audio 3.99 sample
- `click.mpc`: Musepack sample
- `empty10ms.dsf`, `empty10ms.dff`: 10 ms DSD samples
- `empty.spx`: Speex sample

They are used only in Android instrumentation to create disposable files with
JPEG/PNG artwork and compare Android's retriever with the application's reader.
See the bundled TagLib license files for the upstream project's terms.
