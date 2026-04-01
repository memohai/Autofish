# af CLI (Rust)

`af` is the deterministic CLI executor for the Autofish Service.

## Install from npm

```bash
npm i -g @memohjs/af
```

## Build

```bash
cd cli
cargo build --release
```

## Run

```bash
export AF_URL="http://127.0.0.1:8081"
export AF_TOKEN="<token>"
export AF_DB="./af.db"

af health
af observe screen --max-rows 80 --field id --field text --field desc --field resId --field flags
af observe refs --max-rows 80
af observe overlay get
af observe overlay set --enable --mark-scope all --refresh on
af observe screenshot --annotate --max-marks 120 --mark-scope interactive
af act tap --by ref --value @n1
af act tap --by text --value "Settings"
af act tap --xy 540,1200
af act swipe --from 100,1200 --to 900,1200 --duration 300
```

## Command groups

- `health`
- `act`:
  - `tap` (preferred: `--by ref --value @nK`; coordinates: `--xy`; semantic: `--by --value [--exact-match]`)
  - `swipe`, `back`, `home`, `text`, `launch`, `stop`, `key`
- `observe`:
  - `screen`, `refs`, `overlay`, `screenshot`, `top`
- `verify`:
  - `text-contains`, `top-activity`, `node-exists`
- `recover`:
  - `back`, `home`, `relaunch`

## Output and exit code

Each command prints one JSON line.

- `status="ok"`: success, exit code `0`
- `status="failed"`: error, exit code `1`
- `status="interrupted"`: interrupted (SIGINT), exit code `130`
