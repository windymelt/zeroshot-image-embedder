import { NextResponse } from 'next/server';
import fs from 'fs/promises';
import { lookup } from 'mime-types';
import Redis from 'ioredis'; // ioredisをインポート
import sharp from 'sharp'; // sharpをインポート

// Valkey (Redis) クライアントの初期化
// 環境変数から接続情報を取得するか、デフォルト値を使用
const redis = new Redis(process.env.VALKEY_URL || 'redis://localhost:6379');

const CACHE_EXPIRATION_SECONDS = 60 * 60; // キャッシュ有効期限: 1時間

export async function GET(request: Request) {
  const { searchParams } = new URL(request.url);
  const filePathParam = searchParams.get('path');
  const widthParam = searchParams.get('width'); // 幅のパラメータを追加
  const heightParam = searchParams.get('height'); // 高さのパラメータを追加 (任意)

  if (!filePathParam) {
    return NextResponse.json({ error: 'File path is required.' }, { status: 400 });
  }

  // サムネイルのサイズを決定 (デフォルト値を設定)
  const width = widthParam ? parseInt(widthParam, 10) : 200; // デフォルト幅 200px
  const height = heightParam ? parseInt(heightParam, 10) : undefined; // 高さは指定があれば使う

  if (isNaN(width) || (heightParam && isNaN(height as number))) {
    return NextResponse.json({ error: 'Invalid width or height parameter.' }, { status: 400 });
  }

  const filePath = decodeURIComponent(filePathParam);

  // キャッシュキーを生成 (ファイルパスとサイズを含む)
  const cacheKey = `thumbnail:${filePath}:w${width}:h${height || 'auto'}`;

  try {
    // 1. キャッシュを確認
    const cachedData = await redis.getBuffer(cacheKey); // Bufferとして取得
    if (cachedData) {
      console.log(`[API/IMAGE] Cache hit for ${cacheKey}`);
      const mimeType = lookup(filePath) || 'image/jpeg'; // キャッシュ時はMIMEタイプを推測 (JPEGをデフォルトに)
      return new NextResponse(cachedData, {
        status: 200,
        headers: {
          'Content-Type': mimeType,
          'Content-Length': cachedData.length.toString(),
          'X-Cache-Status': 'hit', // キャッシュヒットを示すヘッダー (任意)
          'Cache-Control': 'public, max-age=3600',
        },
      });
    }

    console.log(`[API/IMAGE] Cache miss for ${cacheKey}. Generating thumbnail...`);

    // --- セキュリティ注意 ---
    console.warn(`[API/IMAGE] Security Warning: Accessing file path without validation: ${filePath}`);
    // --- ここまで ---

    // 2. キャッシュがない場合、ファイルを読み込んでリサイズ
    const fileBuffer = await fs.readFile(filePath);

    // sharpでリサイズ
    const thumbnailBuffer = await sharp(fileBuffer)
      .resize(width, height, { fit: 'inside', withoutEnlargement: true }) // 拡大せず、アスペクト比を保ってリサイズ
      .toBuffer();

    // 3. 結果をキャッシュに保存 (非同期で実行し、完了を待たない)
    redis.setex(cacheKey, CACHE_EXPIRATION_SECONDS, thumbnailBuffer).catch(err => {
      console.error(`[API/IMAGE] Failed to set cache for ${cacheKey}:`, err);
    });

    // MIMEタイプを判定
    const mimeType = lookup(filePath) || 'image/jpeg'; // sharpはデフォルトでJPEGを出力することが多い

    // 4. リサイズしたデータをレスポンスとして返す
    return new NextResponse(thumbnailBuffer, {
      status: 200,
      headers: {
        'Content-Type': mimeType,
        'Content-Length': thumbnailBuffer.length.toString(),
        'X-Cache-Status': 'miss', // キャッシュミスを示すヘッダー (任意)
        'Cache-Control': 'public, max-age=3600',
      },
    });

  } catch (error: unknown) {
    if (error instanceof Error && 'code' in error && error.code === 'ENOENT') {
      console.error(`[API/IMAGE] File not found: ${filePath}`);
      return NextResponse.json({ error: 'File not found.' }, { status: 404 });
    } else {
      console.error(`[API/IMAGE] Error processing image ${filePath}:`, error);
      return NextResponse.json({ error: 'Internal server error processing image.' }, { status: 500 });
    }
  }
}