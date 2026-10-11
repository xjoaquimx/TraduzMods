"""Compare GGUFs with production-generated prompts in an isolated local server.

Requires Python 3.11+ and psutil. Downloads nothing and never writes game configs.
This measures warm requests and process RSS/private memory, not Minecraft FPS.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import statistics
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, required=True, help='llama-server executable')
    parser.add_argument('--backend', choices=['cpu', 'cuda'], default='cpu')
    parser.add_argument('--model', action='append', required=True, metavar='LABEL=PATH')
    parser.add_argument('--fixtures', type=Path, required=True, help='modelComparisonFixtures output')
    parser.add_argument('--output', type=Path, required=True, help='new isolated output directory')
    parser.add_argument('--port', type=int, default=18692)
    args = parser.parse_args()
    import psutil
    runtime = args.runtime.resolve(strict=True)
    fixtures = json.loads(args.fixtures.read_text(encoding='utf-8'))
    models = [(item.split('=', 1)[0], Path(item.split('=', 1)[1]).resolve(strict=True)) for item in args.model]
    # Avoid overwriting another experiment or any existing game directory.
    args.output.mkdir(parents=True, exist_ok=False)
    lease = (Path(tempfile.gettempdir()) / 'traduzmods-local-model.lock').open('a+b')
    lease.seek(0)
    try:
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(lease.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.lockf(lease, fcntl.LOCK_EX | fcntl.LOCK_NB, 1)
    except OSError:
        lease.close()
        raise SystemExit('A translator already owns the model lease; close Minecraft before measuring.')
    results = []
    try:
        for label, model in models:
            with model.open('rb') as model_file:
                digest = hashlib.file_digest(model_file, 'sha256').hexdigest()
            token = uuid.uuid4().hex
            environment = os.environ.copy()
            environment.pop('GGML_CUDA_DISABLE_GRAPHS', None)
            command = [str(runtime), '-m', str(model), '--host', '127.0.0.1', '--port', str(args.port),
                       '--api-key', token, '-c', '1024', '-b', '64', '-ub', '32', '-np', '1',
                       '-ngl', '99' if args.backend == 'cuda' else '0', '-t', '2', '--poll', '0',
                       '--prio', '-1', '--cache-ram', '0', '--ctx-checkpoints', '0',
                       '--no-cache-idle-slots', '--no-webui']
            if args.backend == 'cuda':
                command.extend(['--load-mode', 'none'])
            measurements = []
            finished = threading.Event()
            process = None
            start = time.perf_counter()
            safe_label = ''.join(c if c.isalnum() or c in '-_' else '_' for c in label)
            with (args.output / (safe_label + '.log')).open('w', encoding='utf-8') as log:
                process = subprocess.Popen(command, cwd=runtime.parent, env=environment, stdout=log,
                                           stderr=subprocess.STDOUT,
                                           creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))

                def sample():
                    owned = psutil.Process(process.pid)
                    while not finished.is_set():
                        try:
                            memory = owned.memory_info()
                            measurements.append((memory.rss / 1048576, getattr(memory, 'private', memory.vms) / 1048576))
                            if psutil.virtual_memory().available < 256 * 1048576 or memory.rss > 6144 * 1048576:
                                # Only the child created by this benchmark may be terminated.
                                process.terminate()
                                return
                        except psutil.Error:
                            return
                        finished.wait(.05)

                sampler = threading.Thread(target=sample, daemon=True)
                sampler.start()
                headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
                base = 'http://127.0.0.1:' + str(args.port)
                record = {'label': label, 'modelSha256': digest, 'modelBytes': model.stat().st_size,
                          'backend': args.backend, 'contextTokens': 1024, 'threads': 2,
                          'samples': [], 'passed': False, 'meaningReviewed': False}
                try:
                    while True:
                        if process.poll() is not None or time.perf_counter() - start > 90:
                            raise RuntimeError('Native server did not become ready')
                        try:
                            request = urllib.request.Request(base + '/health', headers=headers)
                            with urllib.request.urlopen(request, timeout=.5) as response:
                                if json.load(response).get('status') == 'ok':
                                    break
                        except (OSError, ValueError):
                            pass
                        time.sleep(.1)
                    record['startupMs'] = (time.perf_counter() - start) * 1000
                    for index, fixture in enumerate([{'source': 'Hello.', 'prompt': 'Translate into Brazilian Portuguese: Hello.'}] + fixtures):
                        payload = {'model': 'traduzmods-hy-mt2', 'messages': [{'role': 'user', 'content': fixture['prompt']}],
                                   'temperature': .7, 'top_p': .6, 'top_k': 20, 'repeat_penalty': 1.05,
                                   'seed': 42, 'max_tokens': 384, 'stream': True, 'cache_prompt': True}
                        request = urllib.request.Request(base + '/v1/chat/completions', data=json.dumps(payload).encode(), headers=headers)
                        requested = time.perf_counter()
                        first = None
                        output = ''
                        stop = None
                        with urllib.request.urlopen(request, timeout=120) as response:
                            for raw in response:
                                line = raw.decode('utf-8').strip()
                                if not line.startswith('data: ') or line == 'data: [DONE]':
                                    continue
                                for choice in json.loads(line[6:]).get('choices', []):
                                    content = choice.get('delta', {}).get('content', '')
                                    if content:
                                        if first is None:
                                            first = (time.perf_counter() - requested) * 1000
                                        output += content
                                    stop = choice.get('finish_reason') or stop
                        if not output.strip() or stop != 'stop':
                            raise RuntimeError('Incomplete native translation')
                        if index:
                            record['samples'].append({'source': fixture['source'], 'translation': output,
                                                      'firstTokenMs': first, 'elapsedMs': (time.perf_counter() - requested) * 1000})
                    record['passed'] = True
                    record['medianTranslateMs'] = statistics.median(row['elapsedMs'] for row in record['samples'])
                except Exception as error:
                    record['error'] = type(error).__name__ + ': ' + str(error)
                finally:
                    finished.set()
                    sampler.join(3)
                    if process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(10)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait()
                record['peakResidentMiB'] = max((row[0] for row in measurements), default=None)
                record['peakPrivateMiB'] = max((row[1] for row in measurements), default=None)
                results.append(record)
                (args.output / 'results.json').write_text(json.dumps({'results': results}, indent=2, ensure_ascii=False), encoding='utf-8')
                print(label, 'PASS' if record['passed'] else 'FAIL', flush=True)
                if not record['passed']:
                    raise SystemExit('Benchmark failed; inspect isolated log.')
    finally:
        lease.close()


if __name__ == '__main__':
    main()
