# Android Control Zen (for `amc`)

Assume less. Observe more.  
One action, one check.  
Learn the environment before pushing goals.  
For long runs, keep reporting and validating.  
Failure is normal; blind execution is not.

## Environment
Prefer env vars instead of repeating flags:
```bash
export AMC_URL="http://<host>:9998"
export AMC_TOKEN="<token>"
export AMC_DB="./amc.db"
```
`AMC_URL` is required. If it is missing, commands fail before execution.
`AMC_TOKEN` is required for protected API calls.

## 0. Opening Move (Environment First)
Always start with:
1. `health`
2. `observe top`
3. `observe screen`

If these are unstable, do not proceed.

## 1. Core Loop (Step by Step)
Every action follows the same loop:
1. Execute one action only.
2. Observe immediately (`top` + `screen`).
3. Decide the next action from observed state.

No blind action chains.

## 2. Long-Horizon Operations
For multi-step tasks, report in short intervals:
- Every 1-3 steps: what was done, what was observed, what is next.
- If state is uncertain: stop, observe, then continue.

## 3. Validation Order
Validate in this order:
1. Service availability (`health`)
2. Foreground state (`observe top`)
3. Visual state (`observe screen`)

A command returning `OK` is not enough without state evidence.

## 4. Destructive Action Rule
Keep destructive actions (`act stop`) at the end.  
After running them, treat environment as changed and restart from Section 0.

## 5. Verify Strategy
Use this order:
1. `verify top-activity`
2. `verify text-contains`
3. `verify node-exists` (only when node tree is reliable)

If `verify node-exists` fails, inspect `error.details.hint` and `error.details.screenMeta`.

## 6. Minimal Template
```bash
# action
amc <command>
# observe
amc observe top
amc observe screen --fields id,text,desc --max-rows 80
# verify
amc verify text-contains "Bing"
amc verify node-exists --by text --value "Bing"
```
