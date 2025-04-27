import { NextResponse } from 'next/server';
import OpenAI from 'openai';
import { MeiliSearch } from 'meilisearch'; // Hit型を削除

// 定数 (search.scala.sc から)
const MEILISEARCH_URL = process.env.MEILISEARCH_URL || 'http://localhost:7700';
const MEILISEARCH_INDEX_NAME = 'photo_index';
const OPENAI_EMBEDDING_MODEL = 'text-embedding-3-small';
const MEILISEARCH_EMBEDDER_NAME = 'photoEmbedder';

// OpenAIクライアントの初期化
const openaiApiKey = process.env.OPENAI_TOKEN;
if (!openaiApiKey) {
  console.error('Error: OPENAI_TOKEN environment variable is not set.');
  // 本番環境ではここでエラーを投げるか、適切な処理を行う
}
const openai = new OpenAI({
  apiKey: openaiApiKey,
});

// Meilisearchクライアントの初期化
const client = new MeiliSearch({
  host: MEILISEARCH_URL,
  // MeilisearchにAPIキーが設定されている場合は追加
  // apiKey: process.env.MEILISEARCH_API_KEY,
});

export async function POST(request: Request) {
  if (!openaiApiKey) {
    return NextResponse.json({ error: 'OpenAI API key is not configured.' }, { status: 500 });
  }

  try {
    const { query } = await request.json();

    if (!query || typeof query !== 'string') {
      return NextResponse.json({ error: 'Invalid search query provided.' }, { status: 400 });
    }

    console.log(`API Route: Received search query: ${query}`);

    // 1. OpenAI APIでベクトルを取得
    console.log(`API Route: Requesting embedding for "${query}" using ${OPENAI_EMBEDDING_MODEL}`);
    const embeddingResponse = await openai.embeddings.create({
      model: OPENAI_EMBEDDING_MODEL,
      input: query,
      encoding_format: 'float', // Scalaスクリプトに合わせてfloat形式を指定
    });

    const vector = embeddingResponse.data[0]?.embedding;

    if (!vector) {
      console.error('API Route: Failed to get embedding from OpenAI.');
      return NextResponse.json({ error: 'Failed to get embedding from OpenAI.' }, { status: 500 });
    }
    console.log(`API Route: Embedding received (first 10 elements): ${vector.slice(0, 10).join(', ')}`);


    // 2. Meilisearchでハイブリッド検索を実行
    console.log(`API Route: Searching Meilisearch index "${MEILISEARCH_INDEX_NAME}" with vector and embedder "${MEILISEARCH_EMBEDDER_NAME}"`);
    // Meilisearchのドキュメント型を定義 (filePathを含むことを期待)
    interface PhotoDocument {
      filePath?: string;
      [key: string]: unknown; // 他のフィールドも許容
    }
    const searchResponse = await client.index(MEILISEARCH_INDEX_NAME).search<PhotoDocument>( // 型パラメータを指定
      undefined, // MeilisearchのJSクライアントでは、ベクトル検索時に第一引数(query)はundefinedまたはnullにする
      {
        vector: vector,
        hybrid: {
          embedder: MEILISEARCH_EMBEDDER_NAME,
          // semanticRatio: 0.9 // 必要に応じてセマンティック検索の比率を調整
        },
        attributesToRetrieve: ['filePath'], // filePathのみ取得
        limit: 20 // 取得件数を制限 (必要に応じて調整)
      }
    );

    console.log(`API Route: Meilisearch responded with ${searchResponse.hits.length} hits.`);

    // 3. 結果からfilePathを抽出
    // search<PhotoDocument>で型を指定したので、hitはHit<PhotoDocument>型になる
    const filePaths = searchResponse.hits
      .map((hit) => hit.filePath) // hit.filePathは string | undefined 型になる
      .filter((path): path is string => typeof path === 'string'); // filePathが存在し、string型であるもののみ抽出

    console.log(`API Route: Returning ${filePaths.length} file paths.`);

    return NextResponse.json({ results: filePaths });

  } catch (error) {
    console.error('API Route: An error occurred during the search process:', error);
    // エラーの詳細をログに出力
    if (error instanceof Error) {
      console.error('Error message:', error.message);
      console.error('Error stack:', error.stack);
    } else {
      console.error('Unknown error:', error);
    }
    return NextResponse.json({ error: 'An internal server error occurred.' }, { status: 500 });
  }
}