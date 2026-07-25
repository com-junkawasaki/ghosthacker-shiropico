#!/usr/bin/env python3
"""Authorize the SHIRO & PICO YouTube uploader with minimum OAuth scope."""

import argparse
import os

from google_auth_oauthlib.flow import InstalledAppFlow


YOUTUBE_SCOPES = [
    "https://www.googleapis.com/auth/youtube.upload",
    "https://www.googleapis.com/auth/youtube.readonly",
    "https://www.googleapis.com/auth/youtube.force-ssl",
]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--client-secret", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    flow = InstalledAppFlow.from_client_secrets_file(
        args.client_secret, scopes=YOUTUBE_SCOPES
    )
    credentials = flow.run_local_server(
        host="127.0.0.1",
        port=0,
        authorization_prompt_message="Opening Google OAuth in your browser...",
        success_message="SHIRO & PICO YouTube authorization completed. You may close this tab.",
        open_browser=True,
        access_type="offline",
        prompt="select_account consent",
    )

    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(credentials.to_json())
        output.write("\n")
    print("oauth_result=success")


if __name__ == "__main__":
    main()
