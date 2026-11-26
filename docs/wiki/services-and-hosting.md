# Services and Hosting

**Infantry AI Hosting** gives you servers that your agents can use. The **Sites** tab lists your hosting servers.
Agents can run commands and write small files on them over SSH.

## Sign in from the app

The app signs in **on its own**. Being signed in on the website does not sign in the app.

1. Open the **Services** tab.
2. Click **Sign in** on the account's card. The app opens an approval page in your browser.
3. Approve the sign-in in the browser.

The card shows when the sign-in finishes. If you have no account yet, the Sites tab shows a link to sign up.

You create, start and stop servers on the website. The app only lists them.

## The Sites tab

For each server you see its name, status, address, region and size, and whether SSH is ready. A new server shows
"setting up" for a short time. The list refreshes every 30 seconds; change this with the setting
`sites.refreshSeconds` (0 turns it off).

## What agents can do

| Tool | What it does |
|---|---|
| `infantry_sites_list` | List your servers. |
| `infantry_sites_ssh_exec` | Run a shell command on a server. |
| `infantry_sites_ssh_upload` | Write a small text file on a server. |

Up to 4 SSH calls run at the same time (setting `limits.sites.ssh.maxConcurrent`).

## When the tab says "not loaded"

The Hosting service needs its own add-on program. Until it is installed, the Sites tab says the plugin is not loaded.
