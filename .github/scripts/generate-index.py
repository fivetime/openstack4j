#!/usr/bin/env python3
"""
Fetch GitHub Packages data and generate a static index page.
Usage: python3 generate-index.py <owner> <repo> <token> [commit_sha]
"""

import json
import os
import sys
import urllib.request
import urllib.error
from datetime import datetime, timezone

def api_get(url, token):
    req = urllib.request.Request(url, headers={
        'Accept': 'application/vnd.github+json',
        'Authorization': f'Bearer {token}',
        'X-GitHub-Api-Version': '2022-11-28',
    })
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        print(f"API error {e.code} for {url}: {e.read().decode()}", file=sys.stderr)
        return [] if 'versions' in url or 'packages' in url else {}

def fetch_packages(owner, token):
    url = f'https://api.github.com/users/{owner}/packages?package_type=maven&per_page=100'
    packages = api_get(url, token)
    if not isinstance(packages, list):
        print(f"Unexpected response: {packages}", file=sys.stderr)
        return []

    result = []
    for pkg in packages:
        name = pkg.get('name', '')
        print(f"  Fetching versions for: {name}")
        encoded = name.replace('/', '%2F')
        versions_url = f'https://api.github.com/users/{owner}/packages/maven/{encoded}/versions?per_page=50'
        versions = api_get(versions_url, token)
        result.append({
            'name': name,
            'package_type': pkg.get('package_type', 'maven'),
            'html_url': pkg.get('html_url', ''),
            'created_at': pkg.get('created_at', ''),
            'updated_at': pkg.get('updated_at', ''),
            'versions': [
                {
                    'name': v.get('name', ''),
                    'created_at': v.get('created_at', ''),
                    'updated_at': v.get('updated_at', ''),
                    'html_url': v.get('html_url', ''),
                }
                for v in (versions if isinstance(versions, list) else [])
            ]
        })

    result.sort(key=lambda p: p['name'])
    return result

def generate_html(packages, owner, repo, commit_sha=''):
    build_time = datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')
    short_sha = commit_sha[:7] if commit_sha else ''
    pkg_json = json.dumps(packages, ensure_ascii=False)

    total_versions = sum(len(p['versions']) for p in packages)

    return f'''<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>OpenStack4j Maven Packages</title>
<link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;700&family=DM+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
<style>
  :root {{
    --bg: #0d1117;
    --surface: #161b22;
    --surface-2: #1c2129;
    --border: #30363d;
    --border-active: #58a6ff;
    --text: #e6edf3;
    --text-muted: #8b949e;
    --text-dim: #6e7681;
    --accent: #58a6ff;
    --accent-glow: rgba(88, 166, 255, 0.15);
    --green: #3fb950;
    --orange: #d29922;
    --red: #f85149;
    --purple: #bc8cff;
    --mono: 'JetBrains Mono', monospace;
    --sans: 'DM Sans', sans-serif;
  }}
  * {{ margin: 0; padding: 0; box-sizing: border-box; }}
  body {{
    background: var(--bg);
    color: var(--text);
    font-family: var(--sans);
    min-height: 100vh;
    line-height: 1.6;
  }}

  .header {{
    border-bottom: 1px solid var(--border);
    padding: 2rem 0;
    background: linear-gradient(180deg, #111820 0%, var(--bg) 100%);
  }}
  .container {{ max-width: 960px; margin: 0 auto; padding: 0 1.5rem; }}
  .header-inner {{ display: flex; align-items: center; gap: 1rem; }}
  .logo-icon {{
    width: 44px; height: 44px;
    background: linear-gradient(135deg, var(--accent), var(--purple));
    border-radius: 10px;
    display: flex; align-items: center; justify-content: center;
    font-family: var(--mono); font-weight: 700; font-size: 18px; color: #fff;
    flex-shrink: 0;
  }}
  .header-text h1 {{ font-size: 1.5rem; font-weight: 700; letter-spacing: -0.02em; }}
  .header-text h1 span {{ color: var(--text-muted); font-weight: 400; }}
  .header-meta {{
    display: flex; gap: 1.5rem; margin-top: 0.4rem;
    font-size: 0.78rem; color: var(--text-dim); font-family: var(--mono);
  }}
  .header-meta a {{ color: var(--accent); text-decoration: none; }}
  .header-meta a:hover {{ text-decoration: underline; }}

  .summary {{
    display: flex; gap: 1.5rem; padding: 1rem 0; margin-bottom: 1rem;
    border-bottom: 1px solid var(--border);
    font-size: 0.8rem; color: var(--text-muted); font-family: var(--mono);
  }}
  .summary strong {{ color: var(--text); }}

  .main {{ padding: 2rem 0 4rem; }}

  .package-card {{
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 8px;
    margin-bottom: 0.75rem;
    overflow: hidden;
    transition: border-color 0.2s;
  }}
  .package-card:hover {{ border-color: var(--border-active); }}
  .package-header {{
    padding: 1rem 1.25rem;
    display: flex; align-items: center; justify-content: space-between;
    cursor: pointer; user-select: none;
  }}
  .package-name {{
    font-family: var(--mono); font-size: 0.88rem; font-weight: 600; color: var(--accent);
  }}
  .package-name a {{ color: inherit; text-decoration: none; }}
  .package-name a:hover {{ text-decoration: underline; }}
  .package-meta {{ display: flex; align-items: center; gap: 1rem; }}
  .badge {{
    font-size: 0.65rem; font-family: var(--mono);
    padding: 0.15rem 0.45rem; border-radius: 4px;
    text-transform: uppercase; font-weight: 600;
  }}
  .badge-maven {{ background: var(--accent-glow); color: var(--accent); }}
  .badge-snapshot {{ background: rgba(210, 153, 34, 0.15); color: var(--orange); }}
  .badge-release {{ background: rgba(63, 185, 80, 0.15); color: var(--green); }}
  .version-count {{ font-size: 0.75rem; color: var(--text-muted); font-family: var(--mono); }}
  .chevron {{
    color: var(--text-dim); transition: transform 0.2s; font-size: 0.7rem;
  }}
  .package-card.open .chevron {{ transform: rotate(90deg); }}
  .version-list {{ display: none; border-top: 1px solid var(--border); }}
  .package-card.open .version-list {{ display: block; }}
  .version-item {{
    padding: 0.6rem 1.25rem;
    display: flex; align-items: center; justify-content: space-between;
    border-bottom: 1px solid rgba(48, 54, 61, 0.5);
    font-size: 0.8rem;
    transition: background 0.15s;
  }}
  .version-item:last-child {{ border-bottom: none; }}
  .version-item:hover {{ background: var(--surface-2); }}
  .version-tag {{
    font-family: var(--mono); font-weight: 500; color: var(--text);
    display: flex; align-items: center; gap: 0.5rem;
  }}
  .version-date {{ font-family: var(--mono); font-size: 0.75rem; color: var(--text-dim); }}

  /* Usage section */
  .usage-section {{
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 1.5rem;
    margin-top: 2rem;
  }}
  .usage-section h3 {{
    font-size: 0.85rem; color: var(--text-muted);
    text-transform: uppercase; letter-spacing: 0.05em;
    margin-bottom: 1rem; font-weight: 600;
  }}
  .code-label {{
    font-size: 0.75rem; color: var(--text-dim);
    margin-bottom: 0.4rem; font-family: var(--mono);
    margin-top: 1rem;
  }}
  .code-label:first-of-type {{ margin-top: 0; }}
  .code-block {{
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 1rem;
    font-family: var(--mono);
    font-size: 0.76rem;
    line-height: 1.7;
    overflow-x: auto;
    position: relative;
    white-space: pre;
  }}
  .code-block .t {{ color: var(--red); }}
  .code-block .v {{ color: var(--green); }}
  .code-block .c {{ color: var(--text-dim); font-style: italic; }}
  .copy-btn {{
    position: absolute; top: 0.5rem; right: 0.5rem;
    background: var(--surface-2);
    border: 1px solid var(--border);
    color: var(--text-muted);
    border-radius: 4px;
    padding: 0.2rem 0.5rem;
    font-size: 0.68rem;
    font-family: var(--mono);
    cursor: pointer;
    transition: all 0.15s;
  }}
  .copy-btn:hover {{ color: var(--text); border-color: var(--accent); }}

  .footer {{
    text-align: center; padding: 2rem 0;
    font-size: 0.72rem; color: var(--text-dim);
    font-family: var(--mono);
    border-top: 1px solid var(--border);
  }}
  .footer a {{ color: var(--accent); text-decoration: none; }}
</style>
</head>
<body>

<div class="header">
  <div class="container">
    <div class="header-inner">
      <div class="logo-icon">O4</div>
      <div class="header-text">
        <h1>openstack4j <span>packages</span></h1>
        <div class="header-meta">
          <span>📦 registry: maven</span>
          <a href="https://github.com/{owner}/{repo}">github.com/{owner}/{repo}</a>
          <span>更新于 {build_time}</span>
        </div>
      </div>
    </div>
  </div>
</div>

<div class="main">
  <div class="container">
    <div class="summary">
      <span><strong>{pkg_count}</strong> 包</span>
      <span><strong>{ver_count}</strong> 版本</span>
      <span>commit: <strong><a href="https://github.com/{owner}/{repo}/commit/{commit_sha}" style="color:var(--text);text-decoration:none">{short_sha}</a></strong></span>
    </div>

    <div id="packageList"></div>

    <div class="usage-section">
      <h3>使用方法 / Usage</h3>

      <div class="code-label">~/.m2/settings.xml</div>
      <div class="code-block"><span class="t">&lt;servers&gt;</span>
  <span class="t">&lt;server&gt;</span>
    <span class="t">&lt;id&gt;</span><span class="v">github</span><span class="t">&lt;/id&gt;</span>
    <span class="t">&lt;username&gt;</span><span class="v">YOUR_GITHUB_USERNAME</span><span class="t">&lt;/username&gt;</span>
    <span class="t">&lt;password&gt;</span><span class="v">ghp_xxxxxxxxxxxx</span><span class="t">&lt;/password&gt;</span>  <span class="c">&lt;!-- read:packages scope --&gt;</span>
  <span class="t">&lt;/server&gt;</span>
<span class="t">&lt;/servers&gt;</span><button class="copy-btn" onclick="copyBlock(this)">Copy</button></div>

      <div class="code-label">pom.xml — 添加仓库</div>
      <div class="code-block"><span class="t">&lt;repositories&gt;</span>
  <span class="t">&lt;repository&gt;</span>
    <span class="t">&lt;id&gt;</span><span class="v">github</span><span class="t">&lt;/id&gt;</span>
    <span class="t">&lt;url&gt;</span><span class="v">https://maven.pkg.github.com/{owner}/{repo}</span><span class="t">&lt;/url&gt;</span>
    <span class="t">&lt;snapshots&gt;&lt;enabled&gt;</span><span class="v">true</span><span class="t">&lt;/enabled&gt;&lt;/snapshots&gt;</span>
  <span class="t">&lt;/repository&gt;</span>
<span class="t">&lt;/repositories&gt;</span><button class="copy-btn" onclick="copyBlock(this)">Copy</button></div>

      <div class="code-label">pom.xml — 添加依赖（示例）</div>
      <div class="code-block"><span class="t">&lt;dependency&gt;</span>
  <span class="t">&lt;groupId&gt;</span><span class="v">com.github.openstack4j.core</span><span class="t">&lt;/groupId&gt;</span>
  <span class="t">&lt;artifactId&gt;</span><span class="v">openstack4j-core</span><span class="t">&lt;/artifactId&gt;</span>
  <span class="t">&lt;version&gt;</span><span class="v">3.13-SNAPSHOT</span><span class="t">&lt;/version&gt;</span>
<span class="t">&lt;/dependency&gt;</span>
<span class="t">&lt;dependency&gt;</span>
  <span class="t">&lt;groupId&gt;</span><span class="v">com.github.openstack4j.core.connectors</span><span class="t">&lt;/groupId&gt;</span>
  <span class="t">&lt;artifactId&gt;</span><span class="v">openstack4j-okhttp</span><span class="t">&lt;/artifactId&gt;</span>
  <span class="t">&lt;version&gt;</span><span class="v">3.13-SNAPSHOT</span><span class="t">&lt;/version&gt;</span>
<span class="t">&lt;/dependency&gt;</span><button class="copy-btn" onclick="copyBlock(this)">Copy</button></div>
    </div>
  </div>
</div>

<div class="footer">
  Auto-generated by <a href="https://github.com/{owner}/{repo}/actions">GitHub Actions</a> · {build_time}
</div>

<script>
const PACKAGES = {pkg_json};

function render() {{
  const container = document.getElementById('packageList');
  let html = '';

  for (const pkg of PACKAGES) {{
    const versions = pkg.versions || [];
    const vItems = versions.map(v => {{
      const isSnap = v.name.includes('SNAPSHOT');
      const badge = isSnap
        ? '<span class="badge badge-snapshot">snapshot</span>'
        : '<span class="badge badge-release">release</span>';
      const date = v.updated_at
        ? new Date(v.updated_at).toLocaleDateString('zh-CN', {{
            year:'numeric', month:'2-digit', day:'2-digit',
            hour:'2-digit', minute:'2-digit'
          }})
        : '';
      return `<div class="version-item">
        <span class="version-tag">${{v.name}} ${{badge}}</span>
        <span class="version-date">${{date}}</span>
      </div>`;
    }}).join('');

    const ghUrl = pkg.html_url || '#';
    html += `<div class="package-card" onclick="this.classList.toggle('open')">
      <div class="package-header">
        <span class="package-name"><a href="${{ghUrl}}" target="_blank" onclick="event.stopPropagation()">${{pkg.name}}</a></span>
        <div class="package-meta">
          <span class="badge badge-maven">maven</span>
          <span class="version-count">${{versions.length}} 版本</span>
          <span class="chevron">▶</span>
        </div>
      </div>
      <div class="version-list" onclick="event.stopPropagation()">
        ${{vItems || '<div class="version-item"><span class="version-tag" style="color:var(--text-dim)">无版本信息</span></div>'}}
      </div>
    </div>`;
  }}

  container.innerHTML = html;
}}

function copyBlock(btn) {{
  const block = btn.parentElement;
  const text = block.innerText.replace(/Copy|Copied!/g, '').trim();
  navigator.clipboard.writeText(text).then(() => {{
    btn.textContent = 'Copied!';
    setTimeout(() => btn.textContent = 'Copy', 1500);
  }});
}}

render();
</script>

</body>
</html>'''.format(
        owner=owner,
        repo=repo,
        build_time=build_time,
        commit_sha=commit_sha,
        short_sha=short_sha,
        pkg_count=len(packages),
        ver_count=total_versions,
        pkg_json=pkg_json,
    )


def main():
    if len(sys.argv) < 4:
        print("Usage: python3 generate-index.py <owner> <repo> <token> [commit_sha]")
        sys.exit(1)

    owner = sys.argv[1]
    repo = sys.argv[2]
    token = sys.argv[3]
    commit_sha = sys.argv[4] if len(sys.argv) > 4 else ''

    print(f"Fetching packages for {owner}/{repo}...")
    packages = fetch_packages(owner, token)
    print(f"Found {len(packages)} packages")

    html = generate_html(packages, owner, repo, commit_sha)

    os.makedirs('_site', exist_ok=True)
    with open('_site/index.html', 'w', encoding='utf-8') as f:
        f.write(html)

    print(f"Generated _site/index.html ({len(html)} bytes)")


if __name__ == '__main__':
    main()
