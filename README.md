# jenkins-atom-http-request

The [http_request](https://plugins.jenkins.io/http_request/) plugin (≈30k installations) as an **atom**: one Java
file, no plugin, no dependency.

Calls an endpoint from the build and exposes the result to later steps:

* `url`, `method`, `headers`, `body` — `${ENV}` expanded, so `.../build/${BUILD_NUMBER}` works.
* `expectStatus` — `200`, `2xx`, `200,201`; anything else fails the build with the actual code in the message.
* `token` / `user` + `password` — Jenkins credentials ids, resolved by the runner into `.secrets.json`.
* `saveTo` — write the body into the workspace and archive it as an artifact.

Outputs: `status`, `body` (≤ 256 KiB), `headers`, `durationMs`, `savedFile` — usable as `ATOM_STATUS` etc. in later
steps or as `runAtom(...)['status']` in a pipeline.

## Why this is the obvious first port

It is a step, not a plugin: no UI, no job configuration, no core contract. A pipeline calls it with
`runAtom(atom: 'http-request', version: '1.0.0', inputs: [url: '...'])`, it appears in the atom center with a form,
and there is nothing left for a Jenkins plugin to own.

## Build and smoke test

```bash
./build.sh             # → dist/http-request/{http-request.jar,task.json}
./tools/smoke.sh       # starts a local HTTP server: 200 + saved body, 404 failure, unreachable host
```

## Publish

```bash
tools/publish-atom.sh --center <center-dir> --atom-dir dist/http-request --version 1.0.0 \
    --key key.pem --key-id <keyId>     # tools/ 来自 jenkins-atom-plugin 仓库
```
