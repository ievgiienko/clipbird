import js from '@eslint/js';
import globals from 'globals';

export default [
    js.configs.recommended,
    {
        files: ['src/main/resources/static/js/**/*.js'],
        languageOptions: {
            ecmaVersion: 2023,
            sourceType: 'module',
            globals: globals.browser,
        },
        rules: {
            // The transfer code deliberately ignores several failures, and the reason
            // is written next to each one. An empty block with a comment is fine.
            'no-empty': ['error', { allowEmptyCatch: true }],
            // `const { data, ...meta } = record` отбрасывает поле намеренно.
            'no-unused-vars': ['error', { argsIgnorePattern: '^_', ignoreRestSiblings: true }],
        },
    },
];
