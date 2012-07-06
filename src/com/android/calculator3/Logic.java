/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calculator3;

import android.os.Handler;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.EditText;
import android.app.Activity;

import java.util.Locale;

import org.achartengine.GraphicalView;
import org.achartengine.model.XYSeries;
import org.javia.arity.Function;
import org.javia.arity.Symbols;
import org.javia.arity.SyntaxException;

import com.android.calculator3.CalculatorDisplay.Scroll;

class Logic {
    private CalculatorDisplay mDisplay;
    private Symbols mSymbols = new Symbols();
    private Function mFunction;
    private History mHistory;
    private String  mResult = "";
    private boolean mIsError = false;
    private int mLineLength = 0;
    private Graph mGraph;
    private Activity mContext;

    private static final String INFINITY_UNICODE = "\u221e";

    public static final String MARKER_EVALUATE_ON_RESUME = "?";

    // the two strings below are the result of Double.toString() for Infinity & NaN
    // they are not output to the user and don't require internationalization
    private static final String INFINITY = "Infinity";
    private static final String NAN      = "NaN";

    static final char MINUS = '\u2212';

    private final String mErrorString;
    private final String mSinString;
    private final String mCosString;
    private final String mTanString;
    private final String mLogString;
    private final String mLnString;
    private final String mModString;

    public final static int DELETE_MODE_BACKSPACE = 0;
    public final static int DELETE_MODE_CLEAR = 1;

    private int mDeleteMode = DELETE_MODE_BACKSPACE;

    public interface Listener {
        void onDeleteModeChange();
    }

    private Listener mListener;

    Logic(Activity context, History history, CalculatorDisplay display) {
        mContext = context;
        
        mErrorString = context.getResources().getString(R.string.error);
        mSinString = context.getResources().getString(R.string.sin);
        mCosString = context.getResources().getString(R.string.cos);
        mTanString = context.getResources().getString(R.string.tan);
        mLogString = context.getResources().getString(R.string.lg);
        mLnString = context.getResources().getString(R.string.error);
        mModString = context.getResources().getString(R.string.mod);

        mHistory = history;
        mDisplay = display;
        mDisplay.setLogic(this);
    }
    
    public void setGraph(Graph graph) {
        mGraph = graph;
    }

    public void setListener(Listener listener) {
        this.mListener = listener;
    }

    public void setDeleteMode(int mode) {
        if (mDeleteMode != mode) {
            mDeleteMode = mode;
            mListener.onDeleteModeChange();
        }
    }

    public int getDeleteMode() {
        return mDeleteMode;
    }

    void setLineLength(int nDigits) {
        mLineLength = nDigits;
    }

    boolean eatHorizontalMove(boolean toLeft) {
        EditText editText = mDisplay.getEditText();
        int cursorPos = editText.getSelectionStart();
        return toLeft ? cursorPos == 0 : cursorPos >= editText.length();
    }

    public String getText() {
        return mDisplay.getText().toString();
    }
    
    private void setText(String text) {
        clear(false);
        mDisplay.insert(text);
    }

    void insert(String delta) {
        if(delta.equals(mContext.getResources().getString(R.string.solveForX)) || delta.equals(mContext.getResources().getString(R.string.solveForY))){
            WolframAlpha.solve(getText() + ", " + delta, new Handler(), 
                    new WolframAlpha.ResultsRunnable(){
                        @Override
                        public void run() {
                            String text = "";
                            for(String s : results){
                                text += s + ", ";
                            }
                            if(text.length()>2) text = text.substring(0, text.length()-2);
                            setText(text);
                        }
                    }, 
                    new Runnable(){
                        @Override
                        public void run() {
                            setText(mContext.getResources().getString(R.string.error));
                            mIsError = true;
                        }
                    });
            return;
        }
        mDisplay.insert(delta);
        setDeleteMode(DELETE_MODE_BACKSPACE);
        updateGraph(mGraph);
    }

    public void onTextChanged() {
        setDeleteMode(DELETE_MODE_BACKSPACE);
    }

    public void resumeWithHistory() {
        clearWithHistory(false);
    }

    private void clearWithHistory(boolean scroll) {
        String text = mHistory.getText();
        if (MARKER_EVALUATE_ON_RESUME.equals(text)) {
            if (!mHistory.moveToPrevious()) {
                text = "";
            }
            text = mHistory.getText();
            evaluateAndShowResult(text, CalculatorDisplay.Scroll.NONE);
        } else {
            mResult = "";
            mDisplay.setText(
                    text, scroll ? CalculatorDisplay.Scroll.UP : CalculatorDisplay.Scroll.NONE);
            mIsError = false;
        }
    }

    public void clear(boolean scroll) {
        mHistory.enter("");
        mDisplay.setText("", scroll ? CalculatorDisplay.Scroll.UP : CalculatorDisplay.Scroll.NONE);
        cleared();
    }

    void cleared() {
        mResult = "";
        mIsError = false;
        updateHistory();

        setDeleteMode(DELETE_MODE_BACKSPACE);
    }

    boolean acceptInsert(String delta) {
        String text = getText();
        return !mIsError &&
            (!mResult.equals(text) ||
             isOperator(delta) ||
             mDisplay.getSelectionStart() != text.length());
    }

    void onDelete() {
        if (getText().equals(mResult) || mIsError) {
            clear(false);
        } else {
            mDisplay.dispatchKeyEvent(new KeyEvent(0, KeyEvent.KEYCODE_DEL));
            mResult = "";
        }
        updateGraph(mGraph);
    }

    void onClear() {
        clear(mDeleteMode == DELETE_MODE_CLEAR);
    }

    void onEnter() {
        if (mDeleteMode == DELETE_MODE_CLEAR) {
            clearWithHistory(false); // clear after an Enter on result
        }
        else {
            evaluateAndShowResult(getText(), CalculatorDisplay.Scroll.UP);
        }
    }

    public void evaluateAndShowResult(String text, Scroll scroll) {
        try {
            String result = evaluate(text);
            if (!text.equals(result)) {
                mHistory.enter(text);
                mResult = result;
                mDisplay.setText(mResult, scroll);
                setDeleteMode(DELETE_MODE_CLEAR);
            }
        } catch (SyntaxException e) {
            mIsError = true;
            mResult = mErrorString;
            mDisplay.setText(mResult, scroll);
            setDeleteMode(DELETE_MODE_CLEAR);
        }
    }

    void onUp() {
        String text = getText();
        if (!text.equals(mResult)) {
            mHistory.update(text);
        }
        if (mHistory.moveToPrevious()) {
            mDisplay.setText(mHistory.getText(), CalculatorDisplay.Scroll.DOWN);
        }
    }

    void onDown() {
        String text = getText();
        if (!text.equals(mResult)) {
            mHistory.update(text);
        }
        if (mHistory.moveToNext()) {
            mDisplay.setText(mHistory.getText(), CalculatorDisplay.Scroll.UP);
        }
    }

    void updateHistory() {
        String text = getText();
        // Don't set the ? marker for empty text or the error string.
        // There is no need to evaluate those later.
        if (!TextUtils.isEmpty(text) && !TextUtils.equals(text, mErrorString)
                && text.equals(mResult)) {
            mHistory.update(MARKER_EVALUATE_ON_RESUME);
        } else {
            mHistory.update(getText());
        }
    }

    public static final int ROUND_DIGITS = 1;
    String evaluate(String input) throws SyntaxException {
        if (input.trim().equals("")) {
            return "";
        }

        // drop final infix operators (they can only result in error)
        int size = input.length();
        while (size > 0 && isOperator(input.charAt(size - 1))) {
            input = input.substring(0, size - 1);
            --size;
        }

        // delocalize functions (e.g. Spanish localizes "sin" as "sen")
        input = input.replaceAll(mSinString, "sin");
        input = input.replaceAll(mCosString, "cos");
        input = input.replaceAll(mTanString, "tan");
        input = input.replaceAll(mLogString, "log");
        input = input.replaceAll(mLnString, "ln");
        input = input.replaceAll(mModString, "mod");
        double value = 0.0;
        if(input.contains(mContext.getResources().getString(R.string.X)) || input.contains(mContext.getResources().getString(R.string.Y))){
            if(input.contains("=")){
                String[] s = input.split("=");
                mFunction = mSymbols.compile(s[0].toLowerCase());
                value = mFunction.eval(mSymbols.eval(s[1]));
            }
            else{
                return mErrorString;
            }
        }
        else{
            value = mSymbols.eval(input);
        }

        String result = "";
        for (int precision = mLineLength; precision > 6; precision--) {
            result = tryFormattingWithPrecision(value, precision);
            if (result.length() <= mLineLength) {
                break;
            }
        }
        return result.replace('-', MINUS).replace(INFINITY, INFINITY_UNICODE);
    }

    private String tryFormattingWithPrecision(double value, int precision) {
        // The standard scientific formatter is basically what we need. We will
        // start with what it produces and then massage it a bit.
        String result = String.format(Locale.US, "%" + mLineLength + "." + precision + "g", value);
        if (result.equals(NAN)) { // treat NaN as Error
            mIsError = true;
            return mErrorString;
        }
        String mantissa = result;
        String exponent = null;
        int e = result.indexOf('e');
        if (e != -1) {
            mantissa = result.substring(0, e);

            // Strip "+" and unnecessary 0's from the exponent
            exponent = result.substring(e + 1);
            if (exponent.startsWith("+")) {
                exponent = exponent.substring(1);
            }
            exponent = String.valueOf(Integer.parseInt(exponent));
        } else {
            mantissa = result;
        }

        int period = mantissa.indexOf('.');
        if (period == -1) {
            period = mantissa.indexOf(',');
        }
        if (period != -1) {
            // Strip trailing 0's
            while (mantissa.length() > 0 && mantissa.endsWith("0")) {
                mantissa = mantissa.substring(0, mantissa.length() - 1);
            }
            if (mantissa.length() == period + 1) {
                mantissa = mantissa.substring(0, mantissa.length() - 1);
            }
        }

        if (exponent != null) {
            result = mantissa + 'e' + exponent;
        } else {
            result = mantissa;
        }
        return result;
    }

    static boolean isOperator(String text) {
        return text.length() == 1 && isOperator(text.charAt(0));
    }

    static boolean isOperator(char c) {
        //plus minus times div
        return "+\u2212\u00d7\u00f7/*".indexOf(c) != -1;
    }
    
    void updateGraph(final Graph g){
        final String eq = getText();
        
        if(eq.isEmpty()){
            String title = mContext.getResources().getString(R.string.graphTitle) + eq;
            XYSeries series = new XYSeries(title);
            
            try{
            	g.getDataset().removeSeries(g.getSeries());
                g.setSeries(series);
                g.getDataset().addSeries(series);
            }catch (NullPointerException e){
            	e.printStackTrace();
            }

            GraphicalView graph = (GraphicalView) mContext.findViewById(R.id.graphView);
            
            if(graph!=null) graph.repaint();
            
            return;
        }
        if(!eq.contains("=")) return;
        if(eq.endsWith(mContext.getResources().getString(R.string.plus)) || 
           eq.endsWith(mContext.getResources().getString(R.string.minus)) || 
           eq.endsWith(mContext.getResources().getString(R.string.div)) || 
           eq.endsWith(mContext.getResources().getString(R.string.mul)) || 
           eq.endsWith(mContext.getResources().getString(R.string.dot)) ||
           eq.endsWith(mContext.getResources().getString(R.string.coma)) ||
           eq.endsWith(mContext.getResources().getString(R.string.power)) ||
           eq.endsWith(mContext.getResources().getString(R.string.sqrt)) ||
           eq.endsWith(mContext.getResources().getString(R.string.integral)) ||
           eq.endsWith(mContext.getResources().getString(R.string.sin) + "(") || 
           eq.endsWith(mContext.getResources().getString(R.string.cos) + "(") ||
           eq.endsWith(mContext.getResources().getString(R.string.tan) + "(") ||
           eq.endsWith(mContext.getResources().getString(R.string.lg) + "(") || 
           eq.endsWith(mContext.getResources().getString(R.string.mod) + "(") ||
           eq.endsWith(mContext.getResources().getString(R.string.ln) + "(")) return;
        
        final String[] equation = eq.split("=");
        
        if(equation.length == 1) return;
        
        new Thread(new Runnable(){
            public void run(){
                String title = mContext.getResources().getString(R.string.graphTitle) + eq;
                XYSeries series = new XYSeries(title);
                
                if(equation[0].equals(mContext.getResources().getString(R.string.Y))){
                	for(double x=-10;x<=10;x+=0.1){
                        if(!eq.equals(getText())) return;
                        
                        try{
                            mSymbols.define(mContext.getResources().getString(R.string.X), x);
                            series.add(x, mSymbols.eval(equation[1]));
                        } catch(SyntaxException e){
                            e.printStackTrace();
                        }
                	}
                }
                else if(equation[0].equals(mContext.getResources().getString(R.string.X))){
                	for(double y=-10;y<=10;y+=0.1){
                        if(!eq.equals(getText())) return;
                        
                        try{
                            mSymbols.define(mContext.getResources().getString(R.string.Y), y);
                            series.add(mSymbols.eval(equation[1]), y);
                        } catch(SyntaxException e){
                            e.printStackTrace();
                        }
                	}
                }
                else if(equation[1].equals(mContext.getResources().getString(R.string.Y))){
                	for(double x=-10;x<=10;x+=0.1){
                        if(!eq.equals(getText())) return;
                        
                        try{
                            mSymbols.define(mContext.getResources().getString(R.string.X), x);
                            series.add(x, mSymbols.eval(equation[0]));
                        } catch(SyntaxException e){
                            e.printStackTrace();
                        }
                	}
                }
                else if(equation[1].equals(mContext.getResources().getString(R.string.X))){
                	for(double y=-10;y<=10;y+=0.1){
                        if(!eq.equals(getText())) return;
                        
                        try{
                            mSymbols.define(mContext.getResources().getString(R.string.Y), y);
                            series.add(mSymbols.eval(equation[0]), y);
                        } catch(SyntaxException e){
                            e.printStackTrace();
                        }
                	}
                }
                else{
                	for(double x=-10;x<=10;x+=0.5){
                        for(double y=10;y>=-10;y-=0.5){
                            if(!eq.equals(getText())) return;
                            try{
                                mSymbols.define(mContext.getResources().getString(R.string.X), x);
                                mSymbols.define(mContext.getResources().getString(R.string.Y), y);
                                Double leftSide = mSymbols.eval(equation[0]);
                                Double rightSide = mSymbols.eval(equation[1]);
                                if(leftSide < 0 && rightSide < 0){
                                    if(leftSide*0.97 >= rightSide && leftSide*1.03 <= rightSide){
                                        series.add(x, y);
                                        break;
                                    }
                                }
                                else{
                                    if(leftSide*0.97 <= rightSide && leftSide*1.03 >= rightSide){
                                        series.add(x, y);
                                        break;
                                    }
                                }
                            } catch(SyntaxException e){
                                e.printStackTrace();
                            }
                        }
                    }
                }
                
                g.getDataset().removeSeries(g.getSeries());
                g.setSeries(series);
                g.getDataset().addSeries(series);

                GraphicalView graph = (GraphicalView) mContext.findViewById(R.id.graphView);
                
                if(graph!=null) graph.repaint();
            }
        }).start();
    }
    
    void findEigenvalue(){
    	
    }
    
    void findDeterminant(){
    	
    }
    
    void solveMatrix(){
    	
    }
}