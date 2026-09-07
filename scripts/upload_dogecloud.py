#!/usr/bin/env python3
"""Upload the release APK to DogeCloud OSS using AccessToken + S3 temporary credentials."""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

try:
    import boto3
    from botocore.config import Config
except ImportError:
    sys.exit("需要 boto3，请先 python -m pip install boto3")

ROOT = Path(__file__).resolve().parents[1]
API_HOST = "https://api.dogecloud.com"
NO_PROXY = urllib.request.ProxyHandler({})


def clear_proxy() -> None:
    for name in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy"):
        os.environ.pop(name, None)
    os.environ["NO_PROXY"] = "*"
    os.environ["no_proxy"] = "*"


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def load_properties(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        text = line.strip()
        if not text or text.startswith("#") or "=" not in text:
            continue
        key, value = text.split("=", 1)
        out[key.strip()] = value.strip()
    return out


def cfg(name: str, prop: str, props: dict[str, str], default: str = "") -> str:
    return env(name) or props.get(prop, default).strip()


def doge_api(path: str, payload: dict, access_key: str, secret_key: str) -> dict:
    body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False)
    sign_str = f"{path}\n{body}"
    sign = hmac.new(secret_key.encode("utf-8"), sign_str.encode("utf-8"), hashlib.sha1).hexdigest()
    request = urllib.request.Request(
        API_HOST + path,
        data=body.encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"TOKEN {access_key}:{sign}",
            "Content-Type": "application/json",
        },
    )
    opener = urllib.request.build_opener(NO_PROXY)
    try:
        with opener.open(request, timeout=60) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"多吉云 API 失败（{error.code}）：{raw[:500]}") from error
    data = json.loads(raw)
    if data.get("code") != 200:
        raise SystemExit(f"多吉云 API 失败：{data.get('msg') or raw[:500]}")
    return data.get("data") or {}


def auth_query(path: str, key: str) -> str:
    timestamp = int(time.time()) + 10 * 365 * 24 * 3600
    rand = secrets.token_hex(16)
    uid = "0"
    digest = hashlib.md5(f"{path}-{timestamp}-{rand}-{uid}-{key}".encode("utf-8")).hexdigest()
    return f"{timestamp}-{rand}-{uid}-{digest}"


def s3_config() -> Config:
    kwargs = {
        "s3": {"addressing_style": "virtual"},
        "connect_timeout": 30,
        "read_timeout": 300,
        "retries": {"max_attempts": 5, "mode": "standard"},
    }
    try:
        return Config(
            **kwargs,
            request_checksum_calculation="when_required",
            response_checksum_validation="when_required",
        )
    except TypeError:
        return Config(**kwargs)


def put_object(client, bucket: str, key: str, apk_path: str) -> None:
    size = os.path.getsize(apk_path)
    last_error = None
    for attempt in range(1, 4):
        try:
            with open(apk_path, "rb") as handle:
                client.put_object(
                    Bucket=bucket,
                    Key=key,
                    Body=handle,
                    ContentLength=size,
                    ContentType="application/vnd.android.package-archive",
                )
            return
        except Exception as error:  # noqa: BLE001
            last_error = error
            print(f"多吉云上传第 {attempt} 次失败：{error}")
            time.sleep(2 * attempt)
    raise SystemExit(f"多吉云上传失败：{last_error}") from last_error


def write_cos_url(url: str) -> None:
    github_env = os.environ.get("GITHUB_ENV")
    if github_env:
        with open(github_env, "a", encoding="utf-8") as handle:
            handle.write(f"COS_URL<<EOF\n{url}\nEOF\n")
    cos_file = env("COS_URL_FILE") or str(ROOT / ".cos_url")
    Path(cos_file).write_text(url.strip() + "\n", encoding="utf-8")


def main() -> None:
    clear_proxy()
    props = load_properties(ROOT / "dogecloud.properties")
    access_key = cfg("DOGECLOUD_ACCESS_KEY", "accessKey", props)
    secret_key = cfg("DOGECLOUD_SECRET_KEY", "secretKey", props)
    bucket = cfg("DOGECLOUD_BUCKET", "bucket", props, "blogimgshygocn")
    directory = cfg("DOGECLOUD_DIRECTORY", "directory", props, "sysukcbdl").strip("/")
    if directory == ".":
        directory = ""
    domain = cfg("DOGECLOUD_DOMAIN", "domain", props, "doges3.img.shygo.cn")
    domain = domain.removeprefix("https://").removeprefix("http://").strip("/")
    cdn_key = cfg("DOGECLOUD_AUTH_KEY", "authKey", props)
    version = env("VERSION_NAME")
    apk_path = env("APK_PATH")
    if not access_key or not secret_key or not bucket:
        print("未配置多吉云密钥（dogecloud.properties 或环境变量），跳过对象存储上传。")
        return
    if not version or not apk_path or not os.path.isfile(apk_path):
        raise SystemExit("缺少 VERSION_NAME 或 APK_PATH")

    filename = f"sysukcb-{version}.apk"
    key = f"{directory}/{filename}" if directory else filename
    token = doge_api(
        "/auth/tmp_token.json",
        {"channel": "OSS_UPLOAD", "scopes": [f"{bucket}:{key}"]},
        access_key,
        secret_key,
    )
    credentials = token.get("Credentials") or {}
    buckets = token.get("Buckets") or []
    if not credentials or not buckets:
        raise SystemExit(f"临时密钥响应不完整：{json.dumps(token)[:500]}")

    client = boto3.client(
        "s3",
        region_name="automatic",
        aws_access_key_id=credentials["accessKeyId"],
        aws_secret_access_key=credentials["secretAccessKey"],
        aws_session_token=credentials["sessionToken"],
        endpoint_url=buckets[0]["s3Endpoint"],
        config=s3_config(),
    )
    put_object(client, buckets[0]["s3Bucket"], key, apk_path)
    print(f"已上传到多吉云：{bucket}/{key}")

    if not domain:
        print("未配置 DOGECLOUD_DOMAIN，Release 不写 cosUrl。")
        return
    path = f"/{key}"
    url = f"https://{domain}{path}"
    if cdn_key:
        url = f"{url}?auth_key={auth_query(path, cdn_key)}"
    write_cos_url(url)
    print("已写入对象存储下载地址（不会打印完整链接）。")


if __name__ == "__main__":
    main()
