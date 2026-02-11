# AI Image Analysis

PhotoStat can automatically analyze your images using AI vision capabilities to populate metadata fields. This guide covers setup, usage, and the command-line interface for batch processing.

## Table of Contents

- [Overview](#overview)
- [Configuring AI Providers](#configuring-ai-providers)
  - [Choosing a Provider](#choosing-a-provider)
  - [Configuring Claude (Anthropic)](#configuring-claude-anthropic)
  - [Configuring Gemini (Google)](#configuring-gemini-google)
  - [API Costs](#api-costs)
- [Using AI Analysis in the GUI](#using-ai-analysis-in-the-gui)
- [Analysis Caching](#analysis-caching)
- [Customizing the Analysis Prompt](#customizing-the-analysis-prompt)
- [Rating Scale](#rating-scale)
- [Command-Line Interface (CLI)](#command-line-interface-cli)
  - [Basic Usage](#basic-usage)
  - [CLI Options](#cli-options)
  - [Examples](#examples)
  - [Running in Background](#running-in-background)
  - [Token Usage and Cost Tracking](#token-usage-and-cost-tracking)
- [Troubleshooting](#troubleshooting)

---

## Overview

Two AI providers are supported:

- **Claude** (Anthropic) - High-quality analysis with excellent scene understanding
- **Gemini** (Google) - Cost-effective alternative with fast processing

**What the AI Analyzes:**

| Field | Description | Examples |
|-------|-------------|----------|
| **Tags** | Photography style, subjects, mood, technical aspects | "Portrait", "Landscape", "Black and White", "Bokeh" |
| **Persons** | Descriptions of people in the image | "woman in red dress", "elderly man", "child" |
| **Place** | Location if identifiable | "Beach", "Restaurant", "Central Park" |
| **Rating** | Quality rating from * to ***** | Based on composition, sharpness, artistic value |

---

## Configuring AI Providers

### Choosing a Provider

| Provider | Pros | Cons |
|----------|------|------|
| **Claude** | Excellent scene understanding, detailed descriptions | Higher cost |
| **Gemini** | Very cost-effective, fast processing | May be less detailed |

### Configuring Claude (Anthropic)

**Getting an API Key:**

1. Visit [Anthropic Console](https://console.anthropic.com/)
2. Create an account or sign in
3. Navigate to **API Keys**
4. Click **Create Key** and copy the key

**Setup in PhotoStat:**

1. Open **File > Settings**
2. Navigate to the **AI Analysis** tab
3. Select **Claude** as the provider
4. Enter your **API Key** (starts with `sk-ant-...`)
5. Select a **Model**:

   | Model | Description |
   |-------|-------------|
   | claude-sonnet-4-20250514 | Fast, cost-effective (recommended) |
   | claude-opus-4-20250514 | Most capable, higher cost |
   | claude-3-5-sonnet-20241022 | Previous generation Sonnet |
   | claude-3-5-haiku-20241022 | Fastest, lowest cost |

6. Click **Test API Key** to verify
7. Click **OK** to save

### Configuring Gemini (Google)

**Getting an API Key:**

1. Visit [Google AI Studio](https://aistudio.google.com/)
2. Sign in with your Google account
3. Click **Get API Key**
4. Create a new API key and copy it

**Setup in PhotoStat:**

1. Open **File > Settings**
2. Navigate to the **AI Analysis** tab
3. Select **Gemini** as the provider
4. Enter your **API Key**
5. Select a **Model**:

   | Model | Description |
   |-------|-------------|
   | gemini-2.0-flash | Latest, fastest (recommended) |
   | gemini-1.5-flash | Previous generation, very fast |
   | gemini-1.5-pro | Most capable, slower |

6. Click **Test API Key** to verify
7. Click **OK** to save

### API Costs

| Provider | Cost Level | Best For |
|----------|------------|----------|
| Claude Haiku | Low | Large batches, basic tagging |
| Gemini Flash | Very Low | Cost-sensitive batch processing |
| Claude Sonnet | Medium | Balanced quality and cost |
| Gemini Pro | Medium | Detailed analysis |
| Claude Opus | High | Maximum quality |

Monitor usage:
- Claude: [console.anthropic.com](https://console.anthropic.com/)
- Gemini: [aistudio.google.com](https://aistudio.google.com/)

---

## Using AI Analysis in the GUI

1. **Select images** in the search results (use Ctrl+Click or Shift+Click for multiple)

2. Click **Analyze Selected** in the toolbar above the results

   ![Analyze Button](screenshots/analyze-button.png)

3. **Confirm** the analysis - you'll see how many images will be processed

4. **Monitor progress** - a progress dialog shows:
   - Progress bar with completion percentage
   - Current image count (X of Y)
   - Current file being analyzed
   - Error count during processing
   - **Cancel** button to stop after the current image

5. When complete, a summary shows successes, failures, and any errors

6. Results are **automatically saved** to OpenSearch and sidecar files

**Notes:**
- Only JPG, PNG, GIF, and WebP images are supported for analysis
- RAW files cannot be analyzed directly
- API usage incurs costs - each image uses API credits
- Large batches may take several minutes to process

---

## Analysis Caching

PhotoStat caches analysis results to avoid redundant API calls and reduce costs:

- An `analysisHash` is stored in the sidecar file after each analysis
- The hash combines: provider + model + prompt + image (file size + modification time)
- When re-analyzing, images with matching hashes are skipped
- The progress dialog shows "Cached (skipped)" count for unchanged images
- Cache is invalidated when you:
  - Switch AI providers (Claude ↔ Gemini)
  - Change the model in settings
  - Modify the analysis prompt in config.json
  - Edit or replace the image file

---

## Customizing the Analysis Prompt

You can customize how the AI analyzes your images in two ways:

**Via Settings UI:**

1. Open **File > Settings**
2. Navigate to the **AI Analysis** tab
3. Scroll down to the **Analysis Prompt** section
4. Edit the prompt text
5. Click **OK** to save
6. Use **Reset to Default** to restore the built-in prompt

**Via config.json:**

Edit the prompt directly in `~/.photostat/config.json`:

```json
{
  "claude": {
    "api_key": "sk-ant-...",
    "model": "claude-sonnet-4-20250514",
    "analysis_prompt": "Your custom prompt here..."
  }
}
```

Changing the prompt will invalidate the cache, causing all images to be re-analyzed on next run.

---

## Rating Scale

| Rating | Meaning |
|--------|---------|
| * | Poor - significant technical issues, bad composition |
| ** | Below average - noticeable issues, weak composition |
| *** | Average - decent execution, standard composition |
| **** | Good - strong composition, good technique, visually appealing |
| ***** | Excellent - exceptional composition, masterful technique |

---

## Command-Line Interface (CLI)

PhotoStat includes a CLI mode for batch image analysis that can run in the background without tying up the GUI.

> **Note:** The CLI requires the cross-platform JAR (`photostat-java-*-executable.jar`) and Java 21+. The native installers (MSI/DMG) are for the GUI only.

### Basic Usage

```bash
# Show help
java -jar photostat-java-1.6.4-executable.jar --help

# Show CLI analysis help
java -jar photostat-java-1.6.4-executable.jar --analyze --help

# Run analysis on configured directories
java -jar photostat-java-1.6.4-executable.jar --analyze

# Preview what would be analyzed (no API calls)
java -jar photostat-java-1.6.4-executable.jar --analyze --dry-run
```

### CLI Options

| Option | Description |
|--------|-------------|
| `--analyze` | Run batch analysis mode |
| `--dir <path>` | Analyze specific directory (overrides config) |
| `--provider <name>` | Use 'claude' or 'gemini' (overrides config) |
| `--parallel <n>` | Run n parallel analyses (1-8, default: 1) |
| `--dry-run` | Show what would be analyzed without making API calls |
| `--force` | Re-analyze all images, ignoring cache |
| `--quiet, -q` | Minimal output |
| `--no-progress` | Disable progress updates |
| `--help, -h` | Show help |

### Examples

```bash
# Analyze with Gemini instead of configured provider
java -jar photostat-java-1.6.4-executable.jar --analyze --provider gemini

# Analyze a specific directory
java -jar photostat-java-1.6.4-executable.jar --analyze --dir /path/to/photos

# Re-analyze everything (ignore cache)
java -jar photostat-java-1.6.4-executable.jar --analyze --force

# Quiet mode for scripts
java -jar photostat-java-1.6.4-executable.jar --analyze --quiet

# Run with 4 parallel threads for faster processing
java -jar photostat-java-1.6.4-executable.jar --analyze --parallel 4
```

### Running in Background

**Windows PowerShell:**

```powershell
Start-Process -NoNewWindow -FilePath java -ArgumentList "-jar", "photostat-java-1.6.4-executable.jar", "--analyze"
```

**Linux/macOS:**

```bash
nohup java -jar photostat-java-1.6.4-executable.jar --analyze > analysis.log 2>&1 &
```

### What the CLI Does

1. Reads configuration from `~/.photostat/config.json`
2. Connects to OpenSearch
3. Scans configured directories for images
4. Checks sidecar files for cached analysis (skips if hash matches)
5. Analyzes uncached images using the configured AI provider
6. Updates OpenSearch with results
7. Writes results to sidecar files

### Output Example

```
PhotoStat CLI - Batch Image Analysis
=====================================
AI Provider: Gemini
Config: /home/user/.photostat/config.json
Parallel Threads: 4

Scanning directories for images...
Found 150 images, 142 supported for analysis.
Skipping 98 cached images, 44 to analyze.

Starting analysis of 44 images using Gemini (4 threads)...

[1/44] Analyzing: IMG_1234.jpg... OK (tags: 8, rating: ****)
[2/44] Analyzing: IMG_1235.jpg... OK (tags: 5, rating: ***)
...

=====================================
Analysis Complete
=====================================
Total:     44
Success:   43
Failed:    1
Time:      1.2 minutes
Avg:       1.6 seconds/image

Token Usage:
  Input:   125,432 tokens
  Output:  8,521 tokens
  Total:   133,953 tokens
Est. Cost: $0.0159
```

### Token Usage and Cost Tracking

The CLI displays token usage and estimated costs for Gemini API calls. This helps you monitor API usage and costs during batch processing. Costs are estimated based on current Gemini pricing:

| Model | Input (per 1M tokens) | Output (per 1M tokens) |
|-------|----------------------|------------------------|
| gemini-2.0-flash | $0.10 | $0.40 |
| gemini-1.5-flash | $0.075 | $0.30 |
| gemini-1.5-pro | $1.25 | $5.00 |

Note: Claude API does not provide token usage in responses, so cost tracking is only available for Gemini.

---

## Troubleshooting

| Error | Solution |
|-------|----------|
| "API Key Required" | Configure your API key in Settings > AI Analysis |
| "Invalid API key" | Check that the key is correct and active |
| "API error: 429" | Rate limited - wait and try again with fewer images, or use `--parallel` with lower thread count |
| "Analysis failed" | Check internet connection; verify image format is supported |
| "Retrying after rate limit" | The CLI automatically retries with exponential backoff |
