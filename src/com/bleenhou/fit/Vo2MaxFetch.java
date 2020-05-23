package com.bleenhou.fit;

import static java.time.ZoneOffset.UTC;

import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.swing.JFileChooser;

import com.garmin.fit.DateTime;
import com.garmin.fit.Decode;
import com.garmin.fit.Sport;

public class Vo2MaxFetch {

	private static final int DATETIME_MESSAGE_ID = 0;
	private static final int DATETIME_FIELD_ID = 4;
	private static final int SPORT_MESSAGE_ID = 12;
	private static final int SPORT_FIELD_ID = 0;
	private static final int VO2_MAX_MESSAGE_ID = 140;
	private static final int VO2_MAX_FIELD_ID = 7;
	private static final int CADENCE_MESSAGE_ID = 18;
	private static final int CADENCE_FIELD_ID = 18;
	private static final int HR_MESSAGE_ID = 18;
	private static final int HR_FIELD_ID = 16;
	private static final int WEIGHT_MESSAGE_ID = 79;
	private static final int WEIGHT_FIELD_ID = 3;
	private static final double FIELD_VALUE_FACTOR = 65536.0 / 3.5;

	/**
	 * Uses garmin SDK to fetch Vo2Max Value from the provided FIT file
	 * @param path path to the fit file
	 * @return Vo2Max value from the supplied fitFile
	 */
	private static FitData getVo2Max(String path) throws IOException {
		final FitData fitData = new FitData();
		try (FileInputStream in = new FileInputStream(path)) {
			new Decode().read(in, m -> {
				if (m.getNum() == VO2_MAX_MESSAGE_ID)
					fitData.setVo2Max(m.getFieldIntegerValue(VO2_MAX_FIELD_ID) / FIELD_VALUE_FACTOR); // Convert MET to Vo2Max
				if (m.getNum() == SPORT_MESSAGE_ID)
					fitData.setSport(Sport.getByValue(m.getFieldShortValue(SPORT_FIELD_ID)));
				if (m.getNum() == DATETIME_MESSAGE_ID)
					fitData.setTime(new DateTime(m.getFieldIntegerValue(DATETIME_FIELD_ID)).getDate().toInstant().atOffset(UTC).toLocalDateTime());
				if (m.getNum() == CADENCE_MESSAGE_ID)
					fitData.setCadence(m.getFieldByteValue(CADENCE_FIELD_ID));
				if (m.getNum() == HR_MESSAGE_ID)
					fitData.setHr(m.getFieldByteValue(HR_FIELD_ID));
				if (m.getNum() == WEIGHT_MESSAGE_ID)
					fitData.setWeight(m.getFieldIntegerValue(WEIGHT_FIELD_ID));
			}, msgDef -> {});
		}
		return fitData;
	}
	
	public static void main(String[] args) throws Exception {
		
		// Select folder using UI
	    final JFileChooser fa = new JFileChooser();
	    fa.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); 
	    fa.showDialog(null, "Select FIT folder");
		final File folder = fa.getSelectedFile();
		
		// Fetch Vo2Max in each file and sort by date from file name
		final Map<LocalDate, FitData> fitDataPerDate = new TreeMap<>();
		for (File f : folder.listFiles()){
			final FitData fitData = getVo2Max(f.getAbsolutePath());
			if (fitData.getSport() == Sport.RUNNING) {
				final LocalDate time = fitData.getTime().toLocalDate();
				fitDataPerDate.putIfAbsent(time, fitData);
				if (fitData.getVo2Max() > 0 && fitData.getVo2Max() > fitDataPerDate.get(time).getVo2Max()) // Only keep highest vo2Max per day
					fitDataPerDate.put(time, fitData);
			}
		}
		
		// Open browser
		Desktop.getDesktop().browse(createGraph(fitDataPerDate, "VO2Max", f -> f.getVo2Max()).toUri());
		Desktop.getDesktop().browse(createGraph(fitDataPerDate, "AverageHR", f -> f.getHr().byteValue() > 0 ? f.getHr().byteValue() : f.getHr().byteValue() + 255).toUri());
		Desktop.getDesktop().browse(createGraph(fitDataPerDate, "Cadence", f -> f.getCadence().floatValue() * 2).toUri());
		Desktop.getDesktop().browse(createGraph(fitDataPerDate, "Weight", f -> f.getWeight()).toUri());
	}
	
	/**
	 * Create temporary html file with Vo2Max Data
	 */
	private static final Path createGraph(Map<LocalDate, FitData> fitDataPerDate, String name, Function<FitData, Number> f) throws IOException {
		
		// Raw data
		final String data = fitDataPerDate.entrySet().stream().map(e -> String.format("{x: new Date('%s'), y:%.2f}", e.getKey(), f.apply(e.getValue()).doubleValue())).collect(Collectors.joining(","));
		
		// Averaged data
		final double values [] = {0,0,0,0,0,0,0,0,0,0};
		final AtomicInteger index = new AtomicInteger();
		final double [] currentAvg = {0};
		final String avgData = fitDataPerDate.entrySet().stream().map(e -> String.format("{x: new Date('%s'), y:%.2f}", e.getKey(), 
				currentAvg[0] = computeAvg(values, index.getAndIncrement() % values.length, currentAvg[0], f.apply(e.getValue()).doubleValue()))).collect(Collectors.joining(","));
		
		final String text = new Scanner(Vo2MaxFetch.class.getResourceAsStream("template.html"), "UTF-8").useDelimiter("\\A").next();
		final Path resultFile = Files.createTempFile(name, ".html");
		Files.write(resultFile, text.replace("{{DATA}}", data).replace("{{AVGDATA}}", avgData).replace("{{LABEL}}", name).getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
		return resultFile;
	}
	
	
	private static double computeAvg(double[] values, int index, double currentAvg, double currentValue){
		final double avg = computeAvgInner(values, index, currentAvg, currentValue);
		values[index] = currentValue;
		return avg;
	}
	
	private static double computeAvgInner(double[] values, int index, double currentAvg, double currentValue){
		if(values[index] == 0)
        	return ((currentAvg * index) + currentValue) / (index + 1);
        return (((currentAvg * values.length) - values[index ]) + currentValue) / values.length;
	}
	
	

	private static class FitData {
		private double vo2Max;
		private Byte cadence;
		private Byte hr;
		private LocalDateTime time;
		private Sport sport;
		private double weight;
		
		double getVo2Max() {
			return vo2Max;
		}
		
		void setVo2Max(double vo2Max) {
			this.vo2Max = vo2Max;
		}
		
		LocalDateTime getTime() {
			return time;
		}
		
		void setTime(LocalDateTime time) {
			this.time = time;
		}
		
		Sport getSport() {
			return sport;
		}
		
		void setSport(Sport sport) {
			this.sport = sport;
		}

		Byte getCadence() {
			return cadence;
		}

		void setCadence(Byte cadence) {
			this.cadence = cadence;
		}

		Byte getHr() {
			return hr;
		}

		void setHr(Byte hr) {
			this.hr = hr;
		}

		double getWeight() {
			return weight;
		}

		void setWeight(double weight) {
			this.weight = weight;
		}
	}
}
